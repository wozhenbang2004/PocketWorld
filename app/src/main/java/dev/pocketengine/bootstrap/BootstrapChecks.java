package dev.pocketengine.bootstrap;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;

/** Required local storage and the already-packaged UI identity. No download, repair or optional runtime install. */
public final class BootstrapChecks {
    private BootstrapChecks() { }
    public static final class Failure extends Exception {
        public final String code;
        public Failure(String code,String message){super(message);this.code=code;}
    }
    public static String installation(Context context)throws Exception {
        var installed=context.getPackageManager().getPackageInfo(context.getPackageName(),0);var info=context.getApplicationInfo();
        if(!context.getPackageName().equals(installed.packageName)||!context.getPackageName().equals(info.packageName)||info.uid!=android.os.Process.myUid()||android.os.Build.VERSION.SDK_INT<30)throw new Failure("package_incompatible","安装身份或Android版本不符合当前包要求");
        return (installed.versionName==null?"unknown":installed.versionName)+" ("+installed.getLongVersionCode()+")";
    }
    public static void storage(Context context)throws Exception {
        for(File directory:new File[]{context.getFilesDir(),context.getNoBackupFilesDir(),context.getDatabasePath("pocket-world.db").getParentFile()}){
            if(directory==null)throw new Failure("storage_unwritable","本机必要存储目录不可用");if(!directory.exists()&&!directory.mkdirs())throw new Failure("storage_unwritable","本机必要存储目录不可写");
            if(Files.isSymbolicLink(directory.toPath())||!directory.isDirectory()||!directory.canWrite())throw new Failure("storage_unwritable","本机必要存储目录不可核对或不可写");
            File probe=File.createTempFile(".bootstrap-", ".probe",directory);try(FileOutputStream output=new FileOutputStream(probe)){output.write(new byte[]{1,2,3,4});output.getFD().sync();}finally{Files.deleteIfExists(probe.toPath());}
        }
    }
    public static void requiredUi(Context context)throws Exception {
        JSONObject identity;try(InputStream input=context.getAssets().open("pocket-ui/build-identity.json")){byte[] bytes=readAtMost(input,262145);if(bytes.length>262144)throw new Failure("required_resource_incompatible","安装包界面清单过大，未采用未知资源");identity=new JSONObject(new String(bytes,StandardCharsets.UTF_8));}
        if(!"pocketworld-library-web".equals(identity.optString("kind")))throw new Failure("required_resource_incompatible","安装包界面身份不匹配");
        var rows=identity.getJSONArray("files");if(rows.length()==0||rows.length()>1024)throw new Failure("required_resource_incompatible","安装包必要资源清单不可核对");
        boolean entry=false;long total=0;java.util.Set<String> seen=new java.util.HashSet<>();
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);String path=row.getString("path");long expected=row.getLong("bytes");
            if(path.isEmpty()||path.startsWith("/")||path.contains("\\")||java.util.Arrays.asList(path.split("/")).contains("..")||!seen.add(path)||expected<0||expected>67108864||(total=Math.addExact(total,expected))>134217728)throw new Failure("required_resource_incompatible","安装包必要资源路径或大小无效");
            java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");long actual=0;
            try(InputStream input=context.getAssets().open("pocket-ui/"+path)){byte[] bytes=new byte[65536];int n;while((n=input.read(bytes))!=-1){actual+=n;if(actual>expected)throw new Failure("required_resource_incompatible","安装包必要资源长度不匹配");digest.update(bytes,0,n);}}
            StringBuilder sha=new StringBuilder();for(byte b:digest.digest())sha.append(String.format(java.util.Locale.ROOT,"%02x",b&255));if(actual!=expected||!sha.toString().equals(row.getString("sha256")))throw new Failure("required_resource_incompatible","安装包必要资源校验失败");entry|=path.equals("index.html");
        }
        if(!entry)throw new Failure("required_resource_incompatible","安装包缺少必要界面入口");
    }
    private static byte[] readAtMost(InputStream input,int maximum)throws Exception{java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] chunk=new byte[Math.min(maximum,8192)];int n;while(bytes.size()<maximum&&(n=input.read(chunk,0,Math.min(chunk.length,maximum-bytes.size())))!=-1)bytes.write(chunk,0,n);return bytes.toByteArray();}
    /** Header observation never opens SQLite or claims that an uncheckpointed WAL has the same schema. */
    public static Integer observedSchema(File database)throws Exception {
        if(!database.isFile()||database.length()<100)return null;
        try(InputStream input=Files.newInputStream(database.toPath())){byte[] bytes=readAtMost(input,100);if(bytes.length!=100||!new String(bytes,0,16,StandardCharsets.US_ASCII).equals("SQLite format 3\0"))return null;long version=((long)(bytes[60]&255)<<24)|((long)(bytes[61]&255)<<16)|((long)(bytes[62]&255)<<8)|(bytes[63]&255);return version>Integer.MAX_VALUE?null:(int)version;}
    }
}
