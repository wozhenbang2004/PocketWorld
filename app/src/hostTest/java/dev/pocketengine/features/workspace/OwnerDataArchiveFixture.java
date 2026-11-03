package dev.pocketengine.features.workspace;

import android.content.*;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.features.*;
import dev.pocketengine.features.attachments.*;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.local.runtime.ManagedProjectRuntime;
import dev.pocketengine.features.memory.MemoryModule;
import dev.pocketengine.features.remote.RemoteModule;
import dev.pocketengine.features.ssh.SshModule;
import dev.pocketengine.store.*;
import java.io.File;
import java.nio.file.*;

/** Actual production modules over explicitly separate, fresh test-install roots. */
final class OwnerDataArchiveFixture implements AutoCloseable {
    final Context android;final TaskStore tasks;final FeatureHost host;final FeatureContext context;final WorkspaceModule workspace;final LocalModule local;final AttachmentsModule attachments;final MemoryModule memory;final RemoteModule remote;final SshModule ssh;final MemorySourceBridge sources;
    private static final class Install extends ContextWrapper {
        private final File files,cache,databases;private final String preferences;
        Install(Context app,Path directory)throws Exception{super(app);files=Files.createDirectories(directory.resolve("files")).toFile();cache=Files.createDirectories(directory.resolve("cache")).toFile();databases=Files.createDirectories(directory.resolve("databases")).toFile();preferences="c2-install-"+java.util.UUID.nameUUIDFromBytes(directory.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))+"-";}
        @Override public Context getApplicationContext(){return this;}
        @Override public File getFilesDir(){return files;}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){return super.getSharedPreferences(preferences+name,mode);}
        @Override public File getCacheDir(){return cache;}
        @Override public File getDatabasePath(String name){if(name.indexOf('/')>=0)throw new IllegalArgumentException("test database name");return new File(databases,name);}
        @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory);}
        @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory,DatabaseErrorHandler handler){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).getPath(),factory,handler);}
    }
    OwnerDataArchiveFixture(Context app,Path directory)throws Exception {this(app,directory,false);}
    OwnerDataArchiveFixture(Context app,Path directory,boolean nativeProjectSources)throws Exception {
        android=new Install(app,directory);context=new FeatureContext(android,new SettingsStore(android),new SecretVault(android),event->{});tasks=new TaskStore(android);host=new FeatureHost(event->{},tasks,()->false);workspace=new WorkspaceModule(context);local=new LocalModule(context,workspace,ManagedProjectRuntime.unconfigured(directory.resolve("runtime").toFile()));attachments=new AttachmentsModule(context);host.register(workspace);host.register(local);host.register(attachments);sources=nativeProjectSources?new MemorySourceBridge(host,tasks):null;if(sources!=null)sources.bindWorkspace(workspace);memory=sources==null?new MemoryModule(context):new MemoryModule(context,sources.assertionResolver());remote=new RemoteModule(context);ssh=new SshModule(context);host.register(memory);host.register(remote);host.register(ssh);if(sources!=null){host.register(new dev.pocketengine.features.skills.SkillsModule(context,memory::resolveExperience));host.setMemorySynchronizer(sources::synchronize);}
    }
    @Override public void close()throws Exception {try{host.close();}finally{var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);((SQLiteDatabase)field.get(tasks)).close();}}
    static void installFixtureVault(){java.security.Security.addProvider(new java.security.Provider("C2ArchiveFixtureVault",1.0,"Own test-only keystore"){{put("KeyStore.AndroidKeyStore",FixtureKeyStore.class.getName());}});}
    static void removeFixtureVault(){java.security.Security.removeProvider("C2ArchiveFixtureVault");}
    // Exact synthetic keystore fixture used by original ChannelTaskHostTest; no real account keys.
    public static final class FixtureKeyStore extends java.security.KeyStoreSpi {
        private static final javax.crypto.SecretKey KEY=new javax.crypto.spec.SecretKeySpec(new byte[32],"AES");
        public java.security.Key engineGetKey(String alias,char[] password){return KEY;}
        public java.security.cert.Certificate[] engineGetCertificateChain(String alias){return null;}
        public java.security.cert.Certificate engineGetCertificate(String alias){return null;}
        public java.util.Date engineGetCreationDate(String alias){return new java.util.Date(0);}
        public void engineSetKeyEntry(String alias,java.security.Key key,char[] password,java.security.cert.Certificate[] chain){throw new UnsupportedOperationException();}
        public void engineSetKeyEntry(String alias,byte[] key,java.security.cert.Certificate[] chain){throw new UnsupportedOperationException();}
        public void engineSetCertificateEntry(String alias,java.security.cert.Certificate cert){throw new UnsupportedOperationException();}
        public void engineDeleteEntry(String alias){throw new UnsupportedOperationException();}
        public java.util.Enumeration<String> engineAliases(){return java.util.Collections.enumeration(java.util.Collections.singleton("pocketengine.feature-secrets.v1"));}
        public boolean engineContainsAlias(String alias){return alias.equals("pocketengine.feature-secrets.v1");}
        public int engineSize(){return 1;}
        public boolean engineIsKeyEntry(String alias){return engineContainsAlias(alias);}
        public boolean engineIsCertificateEntry(String alias){return false;}
        public String engineGetCertificateAlias(java.security.cert.Certificate cert){return null;}
        public void engineStore(java.io.OutputStream out,char[] password){}
        public void engineLoad(java.io.InputStream in,char[] password){}
    }
}
