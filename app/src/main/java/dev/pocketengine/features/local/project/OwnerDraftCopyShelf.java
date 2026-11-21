package dev.pocketengine.features.local.project;

import dev.pocketengine.features.workspace.JsonFiles;
import java.io.File;
import java.nio.file.*;
import org.json.JSONObject;

/** Reversible private directory move, only before the new copy is opened for work; never deletes files. */
public final class OwnerDraftCopyShelf {
    private static final String HELD="draft-work-unused",USED="draft-work-opened";
    private OwnerDraftCopyShelf(){}
    private static boolean exists(File f){return Files.exists(f.toPath(),LinkOption.NOFOLLOW_LINKS);}
    private static File safe(File dir,String name)throws Exception{
        File f=new File(dir,name);if(!f.getAbsoluteFile().equals(f.getCanonicalFile())||Files.isSymbolicLink(f.toPath()))throw new SecurityException("副本或回收区不能是链接");return f;
    }
    public static File location(File root,String checkout,boolean shelved)throws Exception{
        if(checkout==null||!checkout.matches("[a-f0-9-]{36}"))throw new SecurityException("副本编号不正确");
        return safe(safe(root,shelved?"draft-work-recycle":"checkouts"),checkout);
    }
    public static void markUnused(File newStaging)throws Exception{Files.createFile(safe(newStaging,HELD).toPath());}
    public static boolean unused(File actual)throws Exception{File held=safe(actual,HELD);return held.isFile()&&held.length()==0&&!exists(safe(actual,USED));}
    public static void requireOpenedForContent(File actual)throws Exception{
        if(exists(new File(actual,HELD)))throw new IllegalStateException("这份工作副本尚未打开。请从原改稿打开，再使用文件；本次查询不会把它标为已用。");
    }
    /** Positive durable evidence of the earlier explicit claim, not just absence of the pending marker. */
    static boolean previouslyOpened(File actual)throws Exception{
        Path opened=safe(actual,USED).toPath();
        return !exists(safe(actual,HELD))&&Files.isRegularFile(opened,LinkOption.NOFOLLOW_LINKS)&&Files.size(opened)==0;
    }
    /** Only an explicit owner open may mark a new copy as in use. Metadata reads cannot claim it. */
    public static void claim(File actual)throws Exception{
        File held=new File(actual,HELD);if(!exists(held))return;
        if(!unused(actual))throw new SecurityException("首次使用登记无法核对");
        Files.move(held.toPath(),safe(actual,USED).toPath(),StandardCopyOption.ATOMIC_MOVE);
    }
    public static void move(File root,OwnerDraftWorkCopy.Seed seed,boolean toShelf)throws Exception{
        File from=location(root,toShelf?seed.checkoutId:seed.projectId,!toShelf),to=location(root,toShelf?seed.projectId:seed.checkoutId,toShelf);
        if(!from.isDirectory()||exists(to)||!unused(from))throw new SecurityException("只收起尚未使用的同一工作副本，不移动已打开的工作或覆盖现有目录");
        File parent=to.getParentFile();if(!parent.isDirectory()&&!parent.mkdir())throw new IllegalStateException("无法建立本机工作副本目录");
        File[] entries=parent.listFiles();if(entries==null||entries.length>=40)throw new IllegalStateException("目标副本目录已满，没有丢弃文件");
        Files.move(from.toPath(),to.toPath(),StandardCopyOption.ATOMIC_MOVE);
    }
    /** A catalog row retained for recovery is not permission to create an empty replacement checkout. */
    public static void requireProjectNotShelved(File root,String project)throws Exception{
        if(project==null||!project.matches("[a-f0-9-]{36}"))return; // No derived project uses another identity format.
        File held=location(root,project,true);if(!exists(held))return;
        JSONObject row=JsonFiles.read(safe(held,"checkout.json"));if(!project.equals(row.opt("projectId"))||!row.has(OwnerDraftWorkCopy.FIELD))throw new SecurityException("这一个项目的回收记录无法核对，未新建空工程");
        throw new IllegalStateException("这个独立工作副本已收起；请回原改稿还原，没有新建空工程。");
    }
}
