package dev.pocketengine.browser;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class BrowserNetworkDebuggingTest {
    @Test public void onlyExplicitNonDebugOwnerPathEnablesAndClosesItsOwnSetting(){List<Boolean> changes=new ArrayList<>();BrowserNetworkDebugging session=new BrowserNetworkDebugging(changes::add);assertFalse(session.permitted());assertTrue(changes.isEmpty());try{assertTrue(session.enableForOwner(false,false));assertTrue(session.permitted());assertTrue(session.enabledByThisSession());assertTrue(session.enableForOwner(false,false));assertEquals(List.of(true),changes);}finally{session.close();}assertFalse(session.permitted());assertEquals(List.of(true,false),changes);session.close();assertEquals(2,changes.size());}
    @Test public void existingDebugStateIsNeitherReenabledNorDisabled(){for(boolean debug:new boolean[]{true,false}){List<Boolean> changes=new ArrayList<>();try(BrowserNetworkDebugging session=new BrowserNetworkDebugging(changes::add)){assertTrue(session.enableForOwner(debug,true));assertFalse(session.enabledByThisSession());}assertTrue(changes.isEmpty());}}
    @Test public void absentDebuggableEndpointAndOtherOwnerCannotBeSilentlyTakenOver(){List<Boolean> changes=new ArrayList<>();try(BrowserNetworkDebugging first=new BrowserNetworkDebugging(changes::add);BrowserNetworkDebugging second=new BrowserNetworkDebugging(changes::add)){assertFalse(first.enableForOwner(true,false));assertTrue(changes.isEmpty());assertTrue(first.enableForOwner(false,false));assertFalse(second.enableForOwner(false,false));second.close();assertEquals(List.of(true),changes);}assertEquals(List.of(true,false),changes);}
    @Test public void failedDisableKeepsOwnedStateForAnHonestOwnerRetry(){boolean[] fail={true};BrowserNetworkDebugging session=new BrowserNetworkDebugging(enabled->{if(!enabled&&fail[0])throw new IllegalStateException("fixture provider failure");});session.enableForOwner(false,false);try{assertThrows(IllegalStateException.class,session::close);assertFalse(session.permitted());assertTrue(session.enabledByThisSession());}finally{fail[0]=false;session.close();}assertFalse(session.enabledByThisSession());}
}
