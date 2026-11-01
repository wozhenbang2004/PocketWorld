package dev.pocketengine.features.local.runtime;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;
import org.json.JSONObject;

final class RuntimeFiles {
    private RuntimeFiles() {}
    static String sha256(File file) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(FileInputStream input=new FileInputStream(file)) {
            byte[] buffer=new byte[65536];int n;
            while((n=input.read(buffer))>=0) if(n>0) digest.update(buffer,0,n);
        }
        StringBuilder hex=new StringBuilder();
        for(byte value:digest.digest()) hex.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
        return hex.toString();
    }
    static void write(File file,JSONObject value) throws Exception {
        byte[] bytes=value.toString().getBytes(StandardCharsets.UTF_8);
        File pending=new File(file.getParentFile(),file.getName()+".pending");
        try(FileOutputStream output=new FileOutputStream(pending)) {output.write(bytes);output.getFD().sync();}
        Files.move(pending.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        if(!Arrays.equals(bytes,Files.readAllBytes(file.toPath()))) throw new IOException("runtime_receipt_commit_not_observed");
    }
    static JSONObject read(File file) throws Exception {
        if(file.length()>1048576) throw new IOException("runtime_receipt_too_large");
        return new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
    }
    static File directory(File parent,String name) throws Exception {
        if(!name.matches("[A-Za-z0-9_-]{1,100}")) throw new IllegalArgumentException("runtime_private_name_invalid");
        File child=new File(parent,name);
        if(Files.isSymbolicLink(child.toPath()) || !child.getAbsoluteFile().equals(child.getCanonicalFile()))
            throw new SecurityException("runtime_private_directory_invalid");
        if(!child.isDirectory() && !child.mkdirs()) throw new IOException("runtime_private_directory_unavailable");
        return child;
    }
}
