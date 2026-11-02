package dev.pocketengine.features.local.runtime;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class NativeOfflineNetworkTest {
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    @Test public void ipv4Ipv6AndChildSocketsAreDeniedWhileUnixIpcIsExplicitlyAllowed()throws Exception{
        var supervisor=new NativeProcessSupervisor();ByteArrayOutputStream ambient=new ByteArrayOutputStream();
        String unix="import socket\ntry:\n s=socket.socket(socket.AF_UNIX);s.close();print('UNIX:0')\nexcept OSError as e: print('UNIX:'+str(e.errno))";
        var base=supervisor.execute(List.of("/usr/bin/python3","-c",unix),Map.of(),temp.getRoot(),5000,200,observer(ambient));assertEquals(0,base.exitCode);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();String code="import errno,socket,subprocess,sys\nfor family in (socket.AF_INET,socket.AF_INET6):\n try:\n  socket.socket(family);sys.exit(9)\n except PermissionError as e: assert e.errno==errno.EACCES\n"+unix+"\nsubprocess.run([sys.executable,'-c',\"import errno,socket,sys\\ntry: socket.socket(socket.AF_INET);sys.exit(8)\\nexcept PermissionError as e: assert e.errno==errno.EACCES\"],check=True)\nprint('IPV4_IPV6_CHILD_DENIED')";
        var exit=supervisor.execute(List.of("/usr/bin/python3","-c",code),Map.of(),temp.getRoot(),5000,200,observer(bytes),true);
        assertEquals(new String(bytes.toByteArray()),0,exit.exitCode);assertEquals(0,exit.launchErrno);assertTrue(exit.groupAbsent);assertTrue(new String(bytes.toByteArray()).contains("IPV4_IPV6_CHILD_DENIED"));assertTrue(new String(bytes.toByteArray()).contains(new String(ambient.toByteArray()).trim()));

    }
    private static NativeProcessSupervisor.Observer observer(ByteArrayOutputStream bytes){return new NativeProcessSupervisor.Observer(){public boolean cancelled(){return false;}public boolean output(int stream,byte[] value){bytes.writeBytes(value);return true;}};}
}
