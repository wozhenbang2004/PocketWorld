package dev.pocketengine.features.skills;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.system.StructStat;
import java.io.FileDescriptor;
import java.io.IOException;
import org.json.JSONObject;

/** Android's real abstract Unix sockets. Connection objects and captured descriptors
 * never come from JSON, PIDs, task restoration or Java Socket status getters. */
final class AndroidManagedScriptCallbackTransport implements ManagedScriptCallbackTransport.Factory {
    @Override public ManagedScriptCallbackTransport.Server open(String name)throws Exception{return new Server(name);}
    private static final class Identity {
        final FileDescriptor fd;final long device,inode;
        Identity(FileDescriptor fd)throws Exception {
            if(fd==null||!fd.valid())throw new IOException("script_callback_descriptor_missing");this.fd=fd;
            StructStat stat=Os.fstat(fd);device=stat.st_dev;inode=stat.st_ino;
            Os.fcntlInt(fd,OsConstants.F_SETFL,Os.fcntlInt(fd,OsConstants.F_GETFL,0)|OsConstants.O_NONBLOCK);check(fd);
        }
        void check(FileDescriptor actual)throws Exception {
            if(actual!=fd||!fd.valid())throw new IOException("script_callback_descriptor_changed");StructStat stat=Os.fstat(fd);
            if(stat.st_dev!=device||stat.st_ino!=inode||(stat.st_mode&OsConstants.S_IFMT)!=OsConstants.S_IFSOCK||(Os.fcntlInt(fd,OsConstants.F_GETFL,0)&OsConstants.O_NONBLOCK)==0)throw new IOException("script_callback_descriptor_changed");
        }
    }
    private static final class Server implements ManagedScriptCallbackTransport.Server {
        final String name;final LocalServerSocket socket;final Identity identity;boolean closed;
        Server(String name)throws Exception {
            if(!name.matches("pw-skill-[a-f0-9]{32}"))throw new IOException("script_callback_endpoint_invalid");this.name=name;socket=new LocalServerSocket(name);
            try{identity=new Identity(socket.getFileDescriptor());}catch(Exception failed){try{socket.close();}catch(Exception cleanup){failed.addSuppressed(cleanup);}throw failed;}
        }
        @Override public JSONObject endpoint()throws Exception{return new JSONObject().put("namespace","abstract").put("name",name);}
        @Override public int hostUid(){return Process.myUid();}
        @Override public long hostPid(){return Process.myPid();}
        @Override public synchronized ManagedScriptCallbackTransport.Connection accept()throws Exception {
            if(closed)return null;identity.check(socket.getFileDescriptor());StructPollfd readable=new StructPollfd();readable.fd=identity.fd;readable.events=(short)OsConstants.POLLIN;
            if(Os.poll(new StructPollfd[]{readable},0)==0)return null;
            if((readable.revents&OsConstants.POLLIN)==0)throw new IOException("script_callback_listener_unavailable");
            // The original listener is O_NONBLOCK even if readiness changes before accept.
            LocalSocket accepted=socket.accept();try{return new Connection(accepted,hostUid());}catch(Exception failed){try{accepted.close();}catch(Exception cleanup){failed.addSuppressed(cleanup);}throw failed;}
        }
        @Override public synchronized void close()throws Exception{if(!closed){closed=true;socket.close();}}
    }
    private static final class Connection implements ManagedScriptCallbackTransport.Connection {
        final LocalSocket socket;final Identity identity;final boolean sameUid;boolean closed;
        Connection(LocalSocket socket,int uid)throws Exception {this.socket=socket;identity=new Identity(socket.getFileDescriptor());sameUid=socket.getPeerCredentials().getUid()==uid;}
        @Override public boolean sameHostUid(){return sameUid;}
        @Override public synchronized int read(byte[] bytes,int offset,int length)throws Exception {
            if(closed)return -1;identity.check(socket.getFileDescriptor());try{int count=Os.read(identity.fd,bytes,offset,length);return count==0?-1:count;}catch(ErrnoException unavailable){if(unavailable.errno==OsConstants.EAGAIN||unavailable.errno==OsConstants.EINTR)return 0;throw unavailable;}
        }
        @Override public synchronized int write(byte[] bytes,int offset,int length)throws Exception {
            if(closed)throw new IOException("script_callback_connection_closed");identity.check(socket.getFileDescriptor());try{return Os.write(identity.fd,bytes,offset,length);}catch(ErrnoException unavailable){if(unavailable.errno==OsConstants.EAGAIN||unavailable.errno==OsConstants.EINTR)return 0;throw unavailable;}
        }
        @Override public synchronized void close()throws Exception{if(!closed){closed=true;socket.close();}}
    }
}
