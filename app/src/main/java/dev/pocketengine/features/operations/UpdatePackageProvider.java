package dev.pocketengine.features.operations;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/** A grantable verified original APK, never a directory or arbitrary file provider. */
public final class UpdatePackageProvider extends ContentProvider {
    @Override public boolean onCreate(){return true;}
    private File file(Uri uri)throws Exception{return PrivateAppUpdates.get(java.util.Objects.requireNonNull(getContext())).fileForInstaller(uri);}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{
        if(!"r".equals(mode))throw new FileNotFoundException("Read only");try{return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY);}catch(Exception invalid){throw new FileNotFoundException("Original verified update unavailable");}
    }
    @Override public String getType(Uri uri){return "application/vnd.android.package-archive";}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){
        try{File file=file(uri);String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;MatrixCursor cursor=new MatrixCursor(columns,1);Object[] values=new Object[columns.length];for(int i=0;i<columns.length;i++)values[i]=columns[i].equals(OpenableColumns.DISPLAY_NAME)?"PocketWorld-update.apk":columns[i].equals(OpenableColumns.SIZE)?file.length():null;cursor.addRow(values);return cursor;}catch(Exception unavailable){return null;}
    }
    @Override public Uri insert(Uri uri,ContentValues values){throw new SecurityException("Read only");}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new SecurityException("Read only");}
    @Override public int delete(Uri uri,String selection,String[] args){throw new SecurityException("Read only");}
}
