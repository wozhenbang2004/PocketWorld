package dev.pocketengine.features.skills;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** The test adapter uses real JDK17 Unix APIs unavailable in Android's SDK. Compile
 * only that declared fixture with the actual JDK; production Android is compiled
 * separately and is never replaced or described as device-tested. */
public final class ManagedScriptCallbackTestSupport {
    private static Class<?> driver;
    private ManagedScriptCallbackTestSupport(){}
    public static synchronized ManagedScriptCallbackTransport.Factory transport()throws Exception {
        if(driver==null){
            Path output=Files.createTempDirectory("skill-callback-host-adapter-");String root=Objects.requireNonNull(System.getProperty("pocket.gateway.source")),cp=System.getProperty("pocketworldHostClasspath",System.getProperty("java.class.path"));
            // Use exactly the locked selected test closure supplied by the existing runner.
            List<String> paths=new ArrayList<>(Arrays.asList(cp.split(java.util.regex.Pattern.quote(File.pathSeparator))));paths.sort(Comparator.comparingInt(path->new File(path).getName().matches("json-[0-9].*\\.jar")?0:1));cp=String.join(File.pathSeparator,paths);
            List<String> command=List.of(new File(System.getProperty("java.home"),"bin/javac").getPath(),"--release","17","-cp",cp,"-d",output.toString(),new File(root,"tools/host-fixtures/SkillCallbackUnixTransport.java").getPath());
            File log=output.resolve("compile.log").toFile();Process compile=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log).start();try{if(!compile.waitFor(30,TimeUnit.SECONDS)||compile.exitValue()!=0)throw new AssertionError("Actual JDK17 Unix test adapter compile failed; own evidence retained at "+output);}finally{stopOwn(compile);}
            Class<?> urlLoader=Class.forName("java.net.URLClassLoader");ClassLoader loader=(ClassLoader)urlLoader.getConstructor(URL[].class,ClassLoader.class).newInstance(new Object[]{new URL[]{output.toUri().toURL()},ManagedScriptCallbackTransport.class.getClassLoader()});
            driver=Class.forName("dev.pocketengine.features.skills.hostfixture.SkillCallbackUnixTransport",true,loader);
        }
        String python=Objects.requireNonNull(System.getProperty("a25.python"));Process uid=new ProcessBuilder(python,"-I","-c","import os; print(os.getuid())").redirectErrorStream(true).start();int actual;try{if(!uid.waitFor(5,TimeUnit.SECONDS)||uid.exitValue()!=0)throw new AssertionError("Only own native UID probe did not settle");actual=Integer.parseInt(new String(uid.getInputStream().readAllBytes(),StandardCharsets.UTF_8).trim());}finally{stopOwn(uid);}
        return (ManagedScriptCallbackTransport.Factory)driver.getConstructor(int.class,String.class).newInstance(actual,System.getProperty("user.name"));
    }
    private static void stopOwn(Process process)throws Exception{if(process.isAlive()){process.destroy();if(!process.waitFor(1,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(2,TimeUnit.SECONDS);}}}
    public static int openServers(ManagedScriptCallbackTransport.Factory factory)throws Exception{return (Integer)factory.getClass().getMethod("openServers").invoke(factory);}
    public static int openClients(ManagedScriptCallbackTransport.Factory factory)throws Exception{return (Integer)factory.getClass().getMethod("openClients").invoke(factory);}
}
