package dev.pocketengine.features.teams;

import android.app.Application;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Test-only application whose actual private SQLite/catalog files survive two isolated JVMs.
 * No task/grant/receipt is reconstructed from diagnostic JSON. No runtime process is retained. */
public final class TeamBudgetPersistentApplication extends Application {
    public static Path ownedRoot() {
        try {
            String supplied=System.getProperty("a25.budgetUpgradeRoot",""),base=System.getProperty("a25.syntheticFixtureBase","");
            if(supplied.isEmpty()||base.isEmpty())throw new IllegalStateException("explicit synthetic fixture base and root required");
            if(!Path.of(supplied).isAbsolute()||!Path.of(base).isAbsolute())throw new SecurityException("synthetic fixture paths must be absolute");
            Path root=Path.of(supplied).normalize(),allowed=Path.of(base).normalize();
            if(!Files.isDirectory(allowed,LinkOption.NOFOLLOW_LINKS)||!root.startsWith(allowed)||root.equals(allowed)||!Files.isRegularFile(root.resolve("synthetic-fixture-owned.marker"),LinkOption.NOFOLLOW_LINKS))
                throw new SecurityException("not this explicit owned synthetic fixture directory");
            for(Path part=root;part!=null;part=part.getParent())if(Files.isSymbolicLink(part))throw new SecurityException("fixture root cannot traverse a symlink");
            return root;
        } catch(RuntimeException failure){throw failure;}
    }
    private File directory(String relative) {
        try {Path path=ownedRoot().resolve(relative);Files.createDirectories(path);return path.toFile();}
        catch(Exception failure){throw new IllegalStateException("original fixture storage unavailable",failure);}
    }
    @Override public File getFilesDir(){return directory("application/files");}
    @Override public File getCacheDir(){return directory("application/cache");}
    @Override public File getCodeCacheDir(){return directory("application/code-cache");}
    @Override public File getNoBackupFilesDir(){return directory("application/no-backup");}
    @Override public File getDataDir(){return directory("application");}
    @Override public File getDatabasePath(String name){
        if(name==null||!name.matches("[A-Za-z0-9_.-]{1,100}"))throw new SecurityException("exact fixture database basename required");
        return new File(directory("application/databases"),name);
    }
    @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory){
        return openOrCreateDatabase(name,mode,factory,null);
    }
    @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory,DatabaseErrorHandler handler){
        int flags=SQLiteDatabase.CREATE_IF_NECESSARY|SQLiteDatabase.OPEN_READWRITE;
        if((mode&MODE_ENABLE_WRITE_AHEAD_LOGGING)!=0)flags|=SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING;
        return SQLiteDatabase.openDatabase(getDatabasePath(name).getPath(),factory,flags,handler);
    }
    @Override public boolean deleteDatabase(String name){return SQLiteDatabase.deleteDatabase(getDatabasePath(name));}
    @Override public String[] databaseList(){String[] names=directory("application/databases").list();return names==null?new String[0]:names;}
}
