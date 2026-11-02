package dev.pocketengine.features.local.runtime;

import java.io.File;
import java.nio.charset.StandardCharsets;

/** Descriptor-anchored capture of one ordinary, singly linked checkout file.
 * This narrows path races; it is not protection from another same-UID process writing the inode. */
public final class WorkspaceFileAccess {
    private WorkspaceFileAccess(){}
    private static final class Native {
        static final Throwable ERROR;
        static{Throwable failure=null;try{System.loadLibrary("pocketprocess");}catch(LinkageError|SecurityException unavailable){failure=unavailable;}ERROR=failure;}
    }
    public static byte[] read(File root,String relative,int maximum)throws Exception {
        if(maximum<1||maximum>64*1024*1024)throw new IllegalArgumentException("workspace_file_budget_invalid");
        if(root==null||!root.isAbsolute()||relative==null||relative.isEmpty()||relative.startsWith("/")||relative.indexOf('\\')>=0||relative.indexOf('\0')>=0)throw new SecurityException("workspace_file_path_invalid");
        for(String part:relative.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals(".."))throw new SecurityException("workspace_file_path_invalid");
        if(Native.ERROR!=null)throw new IllegalStateException("workspace_descriptor_access_unavailable",Native.ERROR);
        try{return capture(utf8(root.getAbsolutePath()),utf8(relative),maximum);}
        catch(UnsatisfiedLinkError unavailable){throw new IllegalStateException("workspace_descriptor_access_unavailable",unavailable);}
    }
    /** Original exact patch only: null before=create, null after=delete. One file is committed
     * through its anchored parent; cross-file transactions and same-UID isolation are not promised. */
    public static void commit(File root,String relative,byte[] before,byte[] after)throws Exception{
        if(before==null&&after==null||before!=null&&before.length>262144||after!=null&&after.length>262144)throw new IllegalArgumentException("workspace_patch_budget_invalid");
        // Reuse the path grammar before native directory creation; an absent file need not be read.
        if(root==null||!root.isAbsolute()||relative==null||relative.isEmpty()||relative.startsWith("/")||relative.indexOf('\\')>=0||relative.indexOf('\0')>=0)throw new SecurityException("workspace_file_path_invalid");
        for(String part:relative.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals(".."))throw new SecurityException("workspace_file_path_invalid");
        if(Native.ERROR!=null)throw new IllegalStateException("workspace_descriptor_access_unavailable",Native.ERROR);
        try{mutate(utf8(root.getAbsolutePath()),utf8(relative),before,after,("pending-"+java.util.UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));}
        catch(UnsatisfiedLinkError unavailable){throw new IllegalStateException("workspace_descriptor_access_unavailable",unavailable);}
    }
    private static byte[] utf8(String value)throws Exception{var encoded=StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(value));byte[] result=new byte[encoded.remaining()];encoded.get(result);return result;}
    private static native void mutate(byte[] root,byte[] relative,byte[] before,byte[] after,byte[] temporary);
    private static native byte[] capture(byte[] root,byte[] relative,int maximum);
}
