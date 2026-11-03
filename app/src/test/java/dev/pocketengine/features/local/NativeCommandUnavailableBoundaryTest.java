package dev.pocketengine.features.local;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
public final class NativeCommandUnavailableBoundaryTest {
    @Test(timeout=30000) public void missingNativeLibraryAdvertisesNoExecutionAndDoesNotFallBackToAnUnownedProcess()throws Exception {
        Path root=Files.createTempDirectory("legacy-no-native-"),tools=Files.createDirectory(root.resolve("tools"));Process child=null;
        try{Path tool=Files.write(tools.resolve("pwd"),"#!/bin/sh\nprintf wrong > fallback-ran\n".getBytes(StandardCharsets.UTF_8));assertTrue(tool.toFile().setExecutable(true));String cp=System.getProperty("pocketworldHostClasspath",System.getProperty("java.class.path"));
            child=new ProcessBuilder(new File(System.getProperty("java.home"),"bin/java").getPath(),"-Djava.library.path="+root.resolve("no-native"),"-cp",cp,NativeCommandUnavailableProbe.class.getName(),root.toString()).redirectErrorStream(true).start();assertTrue("own probe JVM must finish",child.waitFor(20,TimeUnit.SECONDS));ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream in=child.getInputStream()){byte[] page=new byte[4096];for(int n;(n=in.read(page))!=-1;){assertTrue(bytes.size()+n<16384);bytes.write(page,0,n);}}String output=bytes.toString(StandardCharsets.UTF_8.name());assertEquals(output,0,child.exitValue());assertTrue(output.contains("native_unavailable_not_dispatched"));assertFalse(Files.exists(root.resolve("fallback-ran")));
        }finally{if(child!=null&&child.isAlive()){child.destroyForcibly();child.waitFor(5,TimeUnit.SECONDS);}try(var paths=Files.walk(root)){for(Path file:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
    }
}
