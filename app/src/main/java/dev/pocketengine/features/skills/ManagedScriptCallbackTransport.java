package dev.pocketengine.features.skills;

import org.json.JSONObject;

/** Native per-operation Unix I/O only. No task, approval, shell, process launcher,
 * artifact ledger or model-visible factory. Reads/writes/accept are non-blocking. */
public interface ManagedScriptCallbackTransport {
    @FunctionalInterface interface Factory {Server open(String randomName)throws Exception;}
    interface Server extends AutoCloseable {
        JSONObject endpoint()throws Exception;
        int hostUid();
        long hostPid();
        Connection accept()throws Exception;
        @Override void close()throws Exception;
    }
    interface Connection extends AutoCloseable {
        boolean sameHostUid();
        /** 0 would block; -1 EOF. */
        int read(byte[] bytes,int offset,int length)throws Exception;
        /** 0 would block; never internally replays a partial write. */
        int write(byte[] bytes,int offset,int length)throws Exception;
        @Override void close()throws Exception;
    }
}
