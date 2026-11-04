package dev.pocketengine.core;

import java.time.Instant;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic local values only; these tests never contact AWS or load a credential provider. */
public final class AwsSessionCredentialsTest {
    private static final String ACCESS="ASIAABCDEFGHIJKLMNOP", SECRET="FixtureSecretAccessKey0123456789+/=", TOKEN="FixtureSessionToken0123456789+/=";
    private static final long EXPIRY=Instant.parse("2035-01-02T03:04:05Z").toEpochMilli();
    private static JSONObject owner()throws Exception{return new JSONObject().put("accessKeyId",ACCESS).put("secretAccessKey",SECRET).put("sessionToken",TOKEN).put("expiresAt","2035-01-02T03:04:05Z");}
    private static ModelConfig config(AwsSessionCredentials value){return new ModelConfig("","","anthropic.claude-sonnet-4-6",false,20,60,ModelConfig.BEDROCK_PROVIDER,1024).withBedrockSession("us-east-1",value);}
    @Test public void exactOwnerSessionSurvivesOnlyEncryptedVaultEncoding()throws Exception{
        AwsSessionCredentials session=AwsSessionCredentials.fromOwnerInput(owner()), decoded=AwsSessionCredentials.fromVaultString(session.toVaultString());
        assertEquals(ACCESS,decoded.accessKeyId());assertEquals(SECRET,decoded.secretAccessKey());assertEquals(TOKEN,decoded.sessionToken());assertEquals(EXPIRY,decoded.expiresAtEpochMillis());
        assertTrue(config(session).sameCredentials(config(decoded)));assertFalse(session.toString().contains(ACCESS));
    }
    @Test public void partialLongTermExtraOrNonUtcInputsCannotCreateOwnerSession()throws Exception{
        JSONObject missing=owner();missing.remove("sessionToken");
        for(JSONObject invalid:new JSONObject[]{missing,owner().put("profile","default"),owner().put("accessKeyId","AKIAABCDEFGHIJKLMNOP"),owner().put("expiresAt","2035-01-02T03:04:05+00:00"),owner().put("expiresAt",EXPIRY),owner().put("sessionToken",JSONObject.NULL),owner().put("secretAccessKey",12345678901234567L).put("sessionToken",23456789012345678L)}){
            IllegalArgumentException failure=assertThrows(IllegalArgumentException.class,()->AwsSessionCredentials.fromOwnerInput(invalid));
            assertFalse(failure.toString().contains(ACCESS));assertFalse(failure.toString().contains(SECRET));assertFalse(failure.toString().contains(TOKEN));assertNull(failure.getCause());
        }
    }
    @Test public void expiryIsExclusiveAndNeverRefreshesOrChangesOriginalMaterial()throws Exception{
        AwsSessionCredentials session=AwsSessionCredentials.fromOwnerInput(owner());session.requireValidAt(EXPIRY-1);
        assertThrows(IllegalArgumentException.class,()->session.requireValidAt(EXPIRY));assertThrows(IllegalArgumentException.class,()->session.requireValidAt(0));
        assertEquals(EXPIRY,session.expiresAtEpochMillis());assertEquals(TOKEN,session.sessionToken());
    }
    @Test public void corruptedVaultIsContentFreeAndCannotBorrowAnApiKey()throws Exception{
        for(String invalid:new String[]{SECRET,owner().toString(),new JSONObject(AwsSessionCredentials.fromOwnerInput(owner()).toVaultString()).put("expiresAtEpochMillis",1.5).toString(),new JSONObject(AwsSessionCredentials.fromOwnerInput(owner()).toVaultString()).put("secretAccessKey",12345678901234567L).put("sessionToken",23456789012345678L).toString()}){
            IllegalStateException failure=assertThrows(IllegalStateException.class,()->AwsSessionCredentials.fromVaultString(invalid));
            assertFalse(failure.toString().contains(SECRET));assertNull(failure.getCause());
        }
    }
    @Test public void allThreeSensitiveComponentsHaveRedactionAndCrossChunkPrefixes()throws Exception{
        ModelConfig config=config(AwsSessionCredentials.fromOwnerInput(owner()));
        for(String value:new String[]{ACCESS,SECRET,TOKEN}){
            assertTrue(config.containsSensitive("a"+value+"z"));assertFalse(config.redactSensitive("a"+value+"z").contains(value));
            for(int split=1;split<value.length();split++)assertTrue(config.sensitiveSuffixLength("prefix "+value.substring(0,split))>=split);
            assertEquals(0,config.sensitiveSuffixLength("prefix "+value+" "));
        }
        assertFalse(config.containsSensitive(config.publicJson().toString()));assertFalse(config.publicJson().has("awsSession"));assertFalse(config.publicJson().has("apiKey"));
    }
    @Test public void everySessionPartAndExpiryChangeItsOriginalIdentity()throws Exception{
        AwsSessionCredentials session=AwsSessionCredentials.fromOwnerInput(owner());ModelConfig base=config(session);
        for(AwsSessionCredentials changed:new AwsSessionCredentials[]{new AwsSessionCredentials("ASIAQRSTUVWXYZABCDEF",SECRET,TOKEN,EXPIRY),new AwsSessionCredentials(ACCESS,SECRET+"A",TOKEN,EXPIRY),new AwsSessionCredentials(ACCESS,SECRET,TOKEN+"A",EXPIRY),new AwsSessionCredentials(ACCESS,SECRET,TOKEN,EXPIRY+1)})assertFalse(base.sameCredentials(config(changed)));
        assertFalse(base.sameCredentials(config(null)));assertFalse(base.credentialIdentityDigest().contains(ACCESS));
    }
    @Test public void originalSingleApiKeyRedactionAndDigestStayCompatible(){
        ModelConfig old=new ModelConfig("https://example.invalid/v1",SECRET,"model",false,20,60);
        assertEquals("prefix [密钥已隐藏]",old.redactSensitive("prefix "+SECRET));assertTrue(old.sameCredentials(old.withModel("other")));
        assertTrue(old.containsSensitive(SECRET));assertEquals(7,old.sensitiveSuffixLength("x "+SECRET.substring(0,7)));assertEquals(64,old.credentialIdentityDigest().length());
    }
    @Test public void longerSessionTokenRedactsBeforeAnyContainedComponent(){
        String overlapping=TOKEN+ACCESS+SECRET+"remainingTokenBytes0123456789";
        ModelConfig config=config(new AwsSessionCredentials(ACCESS,SECRET,overlapping,EXPIRY));
        assertEquals("before [密钥已隐藏] after",config.redactSensitive("before "+overlapping+" after"));
    }
    @Test public void suffixMatchesDefinitionForOverlappingPrefixesAndBoundedLongToken(){
        java.util.Random random=new java.util.Random(26);
        for(int n=0;n<1000;n++){
            StringBuilder secret=new StringBuilder(),text=new StringBuilder();
            for(int i=0;i<1+random.nextInt(40);i++)secret.append((char)('A'+random.nextInt(3)));
            for(int i=0;i<random.nextInt(70);i++)text.append((char)('A'+random.nextInt(3)));
            int expected=0;for(int size=Math.min(text.length(),secret.length()-1);size>0;size--)if(text.toString().endsWith(secret.substring(0,size))){expected=size;break;}
            assertEquals(expected,AwsSessionCredentials.suffix(text.toString(),secret.toString()));
        }
        assertEquals(0,AwsSessionCredentials.suffix("A".repeat(16382)+"C","A".repeat(16382)+"BC"));
        assertEquals(16383,AwsSessionCredentials.suffix("A".repeat(16383),"A".repeat(16383)+"B"));
    }
    private static String pieces(ModelConfig config,String... chunks){
        String pending="";StringBuilder visible=new StringBuilder();
        for(String chunk:chunks){pending+=chunk;int take=pending.length()-config.sensitiveSuffixLength(pending);visible.append(config.redactSensitive(pending.substring(0,take)));pending=pending.substring(take);}
        return visible.append(config.redactSensitive(pending)).toString();
    }
    @Test public void rawPrefixBeforeRedactionProtectsLongTokenAndBoundaryOverlaps(){
        String token=ACCESS+"OriginalSessionSuffix0123456789";ModelConfig config=config(new AwsSessionCredentials(ACCESS,SECRET,token,EXPIRY));
        assertEquals("[密钥已隐藏]",pieces(config,ACCESS,token.substring(ACCESS.length())));
        String shared="SharedSecretTail",secret="OriginalSecretPrefix"+shared,session=shared+"OriginalSessionTokenSuffix";
        ModelConfig overlapping=config(new AwsSessionCredentials(ACCESS,secret,session,EXPIRY));
        assertEquals(secret.length(),overlapping.sensitiveSuffixLength(secret));
        assertEquals("[密钥已隐藏]",pieces(overlapping,secret,session.substring(shared.length())));
        String all=secret+session.substring(shared.length());
        for(int split=1;split<all.length();split++)assertEquals("split "+split,"[密钥已隐藏]",pieces(overlapping,all.substring(0,split),all.substring(split)));
    }
    @Test public void originalMaskContainingAnApiKeyDoesNotPublishThatKey(){
        for(String secret:new String[]{"[","●","密钥"}){
            ModelConfig config=new ModelConfig("https://example.invalid",secret,"model",false,20,60);
            assertFalse(pieces(config,"prefix ",secret," suffix").contains(secret));
        }
    }
}
