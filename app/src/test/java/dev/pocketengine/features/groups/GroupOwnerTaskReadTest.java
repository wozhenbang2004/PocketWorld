package dev.pocketengine.features.groups;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** Native owner/transport ports are fixtures; the registry and callback boundary are production. */
public class GroupOwnerTaskReadTest {
    @Test public void originalIdentityReadableAfterTransportRevocationWithoutExecutionAuthority() throws Exception {
        try(GroupsModuleTest.Fixture f=new GroupsModuleTest.Fixture()) {
            f.enable("public.answer");String id=f.request("alice");GroupHostPort.Request original=f.last();
            f.ingress.enabled=false;
            String task=f.module.withOwnerTaskRead(id,(request,read)->{
                assertEquals(original.origin().toString(),request.origin().toString());
                read.checkRead();assertThrows(SecurityException.class,read::check);return request.taskId;
            });
            assertEquals(original.taskId,task);assertEquals(1,f.host.admitted);assertEquals(0,f.delivery.sent);
            assertThrows(SecurityException.class,()->f.module.receiveTask(f.event("new","bob"),"public.answer","group","members",1000));
        }
    }
    @Test public void deniedOwnerNeverReceivesOriginalIdentityAndRevocationFencesReturn() throws Exception {
        try(GroupsModuleTest.Fixture f=new GroupsModuleTest.Fixture()) {
            f.enable("public.answer");String id=f.request("alice");AtomicBoolean entered=new AtomicBoolean();
            f.ownerAllowed=false;
            assertThrows(SecurityException.class,()->f.module.withOwnerTaskRead(id,(request,read)->{entered.set(true);return request.taskId;}));
            assertFalse(entered.get());f.ownerAllowed=true;
            assertThrows(SecurityException.class,()->f.module.withOwnerTaskRead(id,(request,read)->{f.ownerAllowed=false;return request.taskId;}));
        }
    }
    @Test public void capturedAuthorityExpiresAfterSuccessfulOrFailedCallback() throws Exception {
        try(GroupsModuleTest.Fixture f=new GroupsModuleTest.Fixture()) {
            f.enable("public.answer");String id=f.request("alice");AtomicReference<GroupHostPort.Authority> saved=new AtomicReference<>();
            f.module.withOwnerTaskRead(id,(request,read)->{saved.set(read);read.checkRead();return request.taskId;});
            assertThrows(SecurityException.class,saved.get()::checkRead);assertThrows(SecurityException.class,saved.get()::check);
            assertThrows(IOException.class,()->f.module.withOwnerTaskRead(id,(request,read)->{saved.set(read);throw new IOException("fixture read failed");}));
            assertThrows(SecurityException.class,saved.get()::checkRead);
        }
    }
    @Test public void moduleCloseDuringReadRevokesAuthorityAndPreventsReturn() throws Exception {
        try(GroupsModuleTest.Fixture f=new GroupsModuleTest.Fixture()) {
            f.enable("public.answer");String id=f.request("alice");
            assertThrows(IllegalStateException.class,()->f.module.withOwnerTaskRead(id,(request,read)->{f.module.close();read.checkRead();return request.taskId;}));
        }
    }
}
