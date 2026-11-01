package dev.pocketengine.browser;

import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public final class BrowserFrameOriginsTest {
    @Test public void oldNativePathDefaultsToSameOriginOnly()throws Exception {assertTrue(BrowserFrameOrigins.sameOriginOnly().origins().isEmpty());assertEquals(Set.of("https://top.example"),BrowserFrameOrigins.admitted("https://top.example",Set.of()));}
    @Test public void ownerChoosesOnlyFiniteExactOriginsNotWholeSitesOrCredentials()throws Exception {for(String invalid:new String[]{"*","https://*.example","https://name:secret@embed.example","https://embed.example/path","https://embed.example?account=private","https://embed.example#account","file:///private","data:text/plain,content","https://embed.example:0"})assertThrows(invalid,Exception.class,()->BrowserFrameOrigins.forOwner(invalid));}
    @Test public void explicitInputNormalizesOnlyTheSameOriginAndCannotBeMutated()throws Exception {BrowserFrameOrigins.Consent consent=BrowserFrameOrigins.forOwner("https://EMBED.example:443/\nhttps://embed.example\nhttp://other.example:8080");assertEquals(Set.of("https://embed.example","http://other.example:8080"),consent.origins());assertThrows(UnsupportedOperationException.class,()->consent.origins().add("https://hidden.example"));assertEquals(2,consent.summary().length());}
    @Test public void ninthDifferentOriginFailsRatherThanSilentlyWideningOrTruncating()throws Exception {StringBuilder text=new StringBuilder();for(int i=0;i<9;i++)text.append("https://frame").append(i).append(".example\n");assertThrows(IllegalArgumentException.class,()->BrowserFrameOrigins.forOwner(text.toString()));}
}
