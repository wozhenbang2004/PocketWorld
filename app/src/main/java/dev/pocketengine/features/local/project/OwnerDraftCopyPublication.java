package dev.pocketengine.features.local.project;

import dev.pocketengine.features.workspace.JsonFiles;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.json.JSONObject;

/** Immutable write-ahead identity for this private draft promotion only. Never opens a project or stores source bytes. */
public final class OwnerDraftCopyPublication {
    private static final String FORMAT="pocketworld.owner-draft-publication-intent.v1",SEAL="draft-work-publication.sha256";
    private OwnerDraftCopyPublication(){}
    private static boolean present(File file){return Files.exists(file.toPath(),LinkOption.NOFOLLOW_LINKS);}
    private static File file(File root,String project)throws Exception{
        if(!project.matches("[a-f0-9-]{36}"))throw new SecurityException("独立工程编号不正确");
        File f=new File(new File(root,"draft-work-intents"),project+".json");
        if(!f.getAbsoluteFile().equals(f.getCanonicalFile())||Files.isSymbolicLink(f.toPath()))throw new SecurityException("不能以链接替换副本建立记录");
        return f;
    }
    private static JSONObject read(File f)throws Exception{
        if(!present(f))return null;
        if(!Files.isRegularFile(f.toPath(),LinkOption.NOFOLLOW_LINKS)||f.length()<2||f.length()>8192)throw new SecurityException("原副本建立记录不可核对，未创建替代工程");
        JSONObject envelope=JsonFiles.read(f),i=envelope.getJSONObject("intent");
        if(!FORMAT.equals(i.opt("format"))||!ProjectSources.digest(i).equals(envelope.opt("sha256"))||
           !f.getName().equals(i.getJSONObject("reference").getString("projectId")+".json"))throw new SecurityException("原副本建立记录已变化");
        return i;
    }
    private static JSONObject identity(OwnerDraftWorkCopy.Review r)throws Exception{
        return new JSONObject().put("format",FORMAT).put("reference",r.seed.view()).put("action",r.seed.action)
            .put("planDigest",r.planDigest).put("originalManifest",r.originalManifest).put("manifestIdentity",r.frozen.identity)
            .put("packageSha256",r.plan().getString("packageSha256")).put("name",r.name);
    }
    private static void same(JSONObject expected,JSONObject actual)throws Exception{
        if(actual==null||!ProjectSources.canonical(expected).equals(ProjectSources.canonical(actual)))throw new SecurityException("这次审阅与原先保留的建立动作不同，未另建或覆盖");
    }
    public static boolean pendingFor(File root,OwnerDraftWorkCopy.Seed seed)throws Exception{
        JSONObject i=read(file(root,seed.projectId));if(i==null)return false;same(seed.view(),i.getJSONObject("reference"));return true;
    }
    public static void checkReview(File root,OwnerDraftWorkCopy.Review r)throws Exception{
        JSONObject saved=read(file(root,r.seed.projectId));if(saved!=null)same(identity(r),saved);
    }
    /** link(2) publishes the synced record atomically without replacing an existing intent, just like native owner backup. */
    private static void syncDirectory(File dir)throws Exception{
        try(var channel=java.nio.channels.FileChannel.open(dir.toPath(),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){channel.force(true);}
    }
    public static void reserve(File root,OwnerDraftWorkCopy.Review r)throws Exception{
        JSONObject expected=identity(r);File f=file(root,r.seed.projectId);JSONObject saved=read(f);if(saved!=null){same(expected,saved);syncDirectory(f.getParentFile());syncDirectory(root);return;}
        File dir=f.getParentFile();if(!dir.isDirectory()&&!dir.mkdir()&&!dir.isDirectory())throw new IllegalStateException("无法保留原副本建立动作");
        byte[] bytes=new JSONObject().put("intent",expected).put("sha256",ProjectSources.digest(expected)).toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>8192)throw new SecurityException("副本建立记录过大");
        File tmp=File.createTempFile("intent-",".pending",dir);
        try{try(FileOutputStream out=new FileOutputStream(tmp)){out.write(bytes);out.getFD().sync();}
            try{Files.createLink(f.toPath(),tmp.toPath());}catch(FileAlreadyExistsException raced){/* verify; never replace */}
            same(expected,read(f));syncDirectory(dir);syncDirectory(root);
        }finally{Files.deleteIfExists(tmp.toPath());}
    }
    /* Published inside the verified directory, together with its full checkout.json by one atomic directory move.
       The often-read Projects overview can verify 64 bytes instead of parsing thousands of checkout baseline entries. */
    public static void sealStaging(File staged,OwnerDraftWorkCopy.Review r)throws Exception{
        byte[] exact=ProjectSources.digest(identity(r)).getBytes(StandardCharsets.US_ASCII);
        Files.write(new File(staged,SEAL).toPath(),exact,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,StandardOpenOption.SYNC);
    }
    /** Ordinary/private registrations stay untouched. Only unfinished (or put-away) draft imports leave the user-facing catalog. */
    public static org.json.JSONArray visibleProjects(File root,org.json.JSONArray all)throws Exception{
        if(!new File(root,"draft-work-intents").isDirectory())return all;
        org.json.JSONArray visible=new org.json.JSONArray();
        for(int n=0;n<all.length();n++){JSONObject row=all.getJSONObject(n);
            if(row.has("sourceImport")){try{requirePresent(root,row.getString("id"),true);}catch(Exception unpublished){continue;}}
            visible.put(row);
        }return visible;
    }
    /** Only today's registered projects (at most 20), not a scan of checkout bodies or draft history.
     * Read the existing publication seal, both matching metadata records and no source-file bytes. */
    public static org.json.JSONArray retainedReferences(File root,org.json.JSONArray projects,String task,String draft)throws Exception{
        return retainedReferences(root,projects,task,draft,false);
    }
    public static org.json.JSONArray retainedReferences(File root,org.json.JSONArray projects,String task,String draft,boolean chatMetadataOnly)throws Exception{
        org.json.JSONArray out=new org.json.JSONArray();
        for(int n=0;n<projects.length();n++){
            JSONObject row=projects.getJSONObject(n),registration=row.optJSONObject("sourceImport");
            if(registration==null)continue;
            String project=row.getString("id");
            try{
                JSONObject intent=read(file(root,project));if(intent==null)continue;
                JSONObject ref=intent.getJSONObject("reference");
                if(!task.equals(ref.opt("taskId"))||!draft.equals(ref.opt("draftId"))||!chatMetadataOnly&&draft.equals(ref.opt("revisionId"))||
                   !project.equals(ref.opt("projectId"))||!intent.getString("action").equals(registration.opt("clientId"))||
                   !intent.getString("packageSha256").equals(registration.opt("packageSha256"))||
                   !Boolean.FALSE.equals(registration.opt("executionAuthorityRestored")))continue;
                requirePresent(root,project,chatMetadataOnly);
                if(present(OwnerDraftCopyShelf.location(root,project,true)))continue; // Incomplete/put-away is not available here.
                out.put(new JSONObject(ref.toString()));
            }catch(Exception unavailable){/* Not a verified, currently published working copy. */ }
        }
        return out;
    }
    /** A catalog row alone is not a materialized workspace. Applies only when THIS project has a draft intent. */
    public static void requireRegisteredCopyPresent(File root,String project)throws Exception{requirePresent(root,project,false);}
    public static void requireOpenedForFormalChange(File root,String project)throws Exception{
        if(project==null||!project.matches("[a-f0-9-]{36}")||!present(new File(root,"draft-work-intents")))return;
        JSONObject i=read(file(root,project));if(i!=null)OwnerDraftCopyShelf.requireOpenedForContent(OwnerDraftCopyShelf.location(root,i.getJSONObject("reference").getString("checkoutId"),false));
    }
    private static void requirePresent(File root,String project,boolean catalogOnly)throws Exception{
        if(project==null||!project.matches("[a-f0-9-]{36}")||!present(new File(root,"draft-work-intents")))return;
        JSONObject i=read(file(root,project));if(i==null)return;
        JSONObject ref=i.getJSONObject("reference");File active=OwnerDraftCopyShelf.location(root,ref.getString("checkoutId"),false);
        File record=new File(active,"checkout.json"),files=new File(active,"files");
        if(!Files.isDirectory(files.toPath(),LinkOption.NOFOLLOW_LINKS)||!Files.isRegularFile(record.toPath(),LinkOption.NOFOLLOW_LINKS))
            throw new IllegalStateException("原改稿已经保留建立动作，但还没核对到完整副本。请回原稿继续，不会新建空工程。");
        Path seal=new File(active,SEAL).toPath();
        if(!Files.isRegularFile(seal,LinkOption.NOFOLLOW_LINKS)||Files.size(seal)!=64||!ProjectSources.digest(i).equals(new String(Files.readAllBytes(seal),StandardCharsets.US_ASCII)))throw new SecurityException("这不是原改稿已经发布的目录，未当作项目打开");
        if(catalogOnly)return;
        JSONObject row=JsonFiles.read(record),src=row.getJSONObject("sourceImport"),receipt=row.getJSONObject(OwnerDraftWorkCopy.FIELD);
        if(!project.equals(row.opt("projectId"))||!ref.getString("checkoutId").equals(row.opt("id"))||!i.getString("action").equals(src.opt("clientId"))||
           !i.getString("packageSha256").equals(src.opt("packageSha256"))||!i.getString("manifestIdentity").equals(src.opt("manifestIdentity"))||
           !Boolean.FALSE.equals(src.opt("executionAuthorityRestored")))throw new SecurityException("副本目录没有原建立动作的发布凭据，未重建");
        for(var keys=ref.keys();keys.hasNext();){String key=keys.next();if(!ProjectSources.canonical(ref.opt(key)).equals(ProjectSources.canonical(receipt.opt(key))))throw new SecurityException("目录不是原稿选定的那个副本");}
    }
}
