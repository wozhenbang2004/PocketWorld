package dev.pocketengine.features.web;

import org.junit.Test;
import static org.junit.Assert.*;

public final class WebQueryGuardTest {
    @Test public void publicTechnicalSearchIsAccepted()throws Exception {
        assertEquals("Android Java 17 web search",WebQueryGuard.requirePublic(" Android Java 17 web search ",512));
        assertEquals("site:example.com AI models",WebQueryGuard.requirePublic("site:example.com AI models",512));
    }
    @Test public void credentialsAndPersonalContactsAreNotDispatched()throws Exception {
        String[] unsafe={
                "my token sk-ABCDEF1234567890abcdef",
                "Bearer abcdef1234567890123456",
                "password=secret123456789",
                "please search me@example.com",
                "eyJhbGciOiJIUzI1NiIs.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcdefghijk1234567890"
        };
        for(String query:unsafe){
            try{WebQueryGuard.requirePublic(query,512);fail("Unsafe query accepted: "+query);}
            catch(SecurityException expected){}
        }
    }
    @Test public void controlCharactersAreRejected()throws Exception {
        try{WebQueryGuard.requirePublic("hello\nsecret",512);fail("newlines must be rejected");}
        catch(IllegalArgumentException expected){}
    }
}
