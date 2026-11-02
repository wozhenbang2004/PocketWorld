package dev.pocketengine.runtime.usage;

import dev.pocketengine.core.RunControl;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.json.JSONObject;

/** Stores only an already captured observation's actual bytes. Never captures or substitutes a screen. */
public final class AnswerSourceFrames {
    private static volatile AnswerSourceFrames host;private final Path directory;
    public AnswerSourceFrames(Path directory)throws Exception{this.directory=directory;Files.createDirectories(directory);}
    public static void install(AnswerSourceFrames nativeHost){host=nativeHost;}
    public static void retain(String task,RunControl control,JSONObject observation){AnswerSourceFrames current=host;if(current==null||observation==null)return;try{current.save(task,control,observation);}catch(Exception unavailable){/* Optional evidence storage never blocks an actual task. */}}
    private synchronized void save(String task,RunControl control,JSONObject observation)throws Exception {
        RunControl.HostRunIdentity run=control.hostRunIdentity();String id=observation.optString("observationId"),mime=observation.optString("imageMimeType","image/jpeg"),image=observation.optString("imageBase64");
        if(run==null||!task.equals(run.taskId())||control.isCancelled()||control.isPaused()||id.isEmpty()||id.length()>200||image.isEmpty()||image.length()>8*1024*1024||!java.util.Set.of("image/png","image/jpeg","image/webp").contains(mime))return;
        byte[] bytes=Base64.getMimeDecoder().decode(image);if(bytes.length<16||bytes.length>6*1024*1024)return;String sha=digest(bytes),file=digest((task+":"+run.runId()+":"+id).getBytes(java.nio.charset.StandardCharsets.UTF_8))+".frame";
        Path target=directory.resolve(file);if(Files.exists(target)&&!sha.equals(digest(Files.readAllBytes(target))))throw new SecurityException("原观察帧身份已存在，不覆盖旧字节");
        if(!Files.exists(target)){Path tmp=Files.createTempFile(directory,"frame-",".tmp");try{Files.write(tmp,bytes);control.check();if(control.isPaused())return;Files.move(tmp,target,java.nio.file.StandardCopyOption.ATOMIC_MOVE);}finally{Files.deleteIfExists(tmp);}}
        long total=0;java.util.List<Path> files;try(var paths=Files.list(directory)){files=paths.filter(p->p.getFileName().toString().endsWith(".frame")).sorted(java.util.Comparator.comparingLong(p->{try{return Files.getLastModifiedTime(p).toMillis();}catch(Exception e){return 0;}})).collect(java.util.stream.Collectors.toList());}for(Path path:files)total+=Files.size(path);for(Path path:files){if(total<=24L*1024*1024)break;long size=Files.size(path);Files.delete(path);total-=size;}
        observation.put("retainedFrame",new JSONObject().put("format","pocketworld.observation-frame.v1").put("taskId",task).put("runId",run.runId()).put("epoch",run.epoch()).put("observationId",id).put("file",file).put("sha256",sha).put("bytes",bytes.length).put("mimeType",mime));
    }
    public synchronized JSONObject read(JSONObject reference)throws Exception {
        if(!"pocketworld.observation-frame.v1".equals(reference.optString("format"))||!reference.optString("file").matches("[a-f0-9]{64}\\.frame")||!reference.optString("sha256").matches("[a-f0-9]{64}"))throw new SecurityException("原观察帧定位不可核对");Path file=directory.resolve(reference.getString("file"));
        String expected=digest((reference.getString("taskId")+":"+reference.getString("runId")+":"+reference.getString("observationId")).getBytes(java.nio.charset.StandardCharsets.UTF_8))+".frame";if(!expected.equals(reference.getString("file"))||!java.util.Set.of("image/png","image/jpeg","image/webp").contains(reference.optString("mimeType")))throw new SecurityException("帧不属于原观察");
        JSONObject result=new JSONObject().put("available",false).put("observationId",reference.getString("observationId"));if(!Files.exists(file))return result.put("reason","original_frame_not_retained_or_evicted");if(Files.size(file)!=reference.getLong("bytes")||Files.size(file)>6*1024*1024)throw new SecurityException("原观察帧字节范围改变");byte[] bytes=Files.readAllBytes(file);if(!reference.getString("sha256").equals(digest(bytes)))throw new SecurityException("原观察帧指纹改变");return result.put("available",true).put("dataUrl","data:"+reference.getString("mimeType")+";base64,"+Base64.getEncoder().encodeToString(bytes));
    }
    private static String digest(byte[] bytes)throws Exception{StringBuilder out=new StringBuilder();for(byte b:java.security.MessageDigest.getInstance("SHA-256").digest(bytes))out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
}
