package dev.pocketengine.features.attachments;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.FileNotFoundException;
import org.json.JSONObject;

/** Only explicit read URI grants expose one imported attachment; no directory or arbitrary path API. */
public final class AttachmentProvider extends ContentProvider {
    @Override public boolean onCreate(){return true;}
    @Override public String getType(Uri uri){try{return metadata(uri).getString("mimeType");}catch(Exception error){return null;}}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] arguments,String sortOrder){try{JSONObject record=metadata(uri);String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;MatrixCursor cursor=new MatrixCursor(columns,1);Object[] values=new Object[columns.length];for(int i=0;i<columns.length;i++)if(OpenableColumns.DISPLAY_NAME.equals(columns[i]))values[i]=record.getString("name");else if(OpenableColumns.SIZE.equals(columns[i]))values[i]=record.getLong("size");cursor.addRow(values);return cursor;}catch(Exception error){throw new IllegalArgumentException("附件不存在或不可读取");}}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{if(!"r".equals(mode))throw new FileNotFoundException("附件仅允许只读访问");try{return ParcelFileDescriptor.open(isView(uri)?snapshots().file(id(uri)):repository().book.file(id(uri)),ParcelFileDescriptor.MODE_READ_ONLY);}catch(Exception failure){throw new FileNotFoundException("附件不存在或不可读取");}}
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException("只读附件提供器");}
    @Override public int delete(Uri uri,String selection,String[] arguments){throw new UnsupportedOperationException("只读附件提供器");}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] arguments){throw new UnsupportedOperationException("只读附件提供器");}
    private AttachmentViewSnapshots snapshots(){return new AttachmentViewSnapshots(getContext());}
    private boolean isView(Uri uri){id(uri);return uri.getPathSegments().size()==2;}
    private JSONObject metadata(Uri uri)throws Exception{return isView(uri)?snapshots().metadata(id(uri)):repository().book.get(id(uri));}
    private AttachmentRepository repository(){return AttachmentRepository.get(getContext());}
    private String id(Uri uri){if(uri==null||!"content".equals(uri.getScheme())||!getContext().getPackageName().concat(".attachments").equals(uri.getAuthority())||!(uri.getPathSegments().size()==1||uri.getPathSegments().size()==2&&"view".equals(uri.getPathSegments().get(0)))||uri.getQuery()!=null||uri.getFragment()!=null)throw new IllegalArgumentException("附件URI无效");String id=uri.getLastPathSegment();if(id==null||!id.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("附件ID无效");return id;}
}
