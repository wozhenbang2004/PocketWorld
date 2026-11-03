package dev.pocketengine.features.local.project.android;

import android.content.Context;
import android.content.ContentResolver;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.provider.DocumentsContract;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.local.project.ProjectDocumentImport;
import java.io.*;

/** Exact native OpenDocument result, one bounded read; no persisted URI or model JSON entrypoint. */
public final class AndroidProjectDocument implements ProjectDocumentImport.Document {
    private final ContentResolver resolver;private final Uri selected;private final RunControl original;private final CancellationSignal signal=new CancellationSignal();private final Runnable stop=signal::cancel;private AssetFileDescriptor descriptor;private boolean closed;
    public AndroidProjectDocument(Context context,Uri nativePickerResult,RunControl original)throws Exception{if(nativePickerResult==null||!"content".equals(nativePickerResult.getScheme())||nativePickerResult.getAuthority()==null||!DocumentsContract.isDocumentUri(context,nativePickerResult)||original==null||original.hasHostRun())throw new SecurityException("请在原系统文档选择器明确选择一个文件");this.original=original;resolver=context.getContentResolver();selected=nativePickerResult;original.onCancel(stop);}
    private void current()throws Exception{if(closed)throw new IllegalStateException("原系统文件选择已结束");original.check();if(original.isPaused()||Thread.currentThread().isInterrupted())throw new InterruptedIOException("原文件读取已取消/暂停");}
    @Override public ProjectDocumentImport.Metadata metadata()throws Exception{current();try(Cursor row=resolver.query(selected,new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_FLAGS},null,null,null,signal)){if(row==null||!row.moveToFirst())throw new IOException("系统没有返回这份文件元数据");String name=row.getString(0),mime=row.getString(1);if(name==null||mime==null)throw new IOException("所选文件缺少名称/类型");Long size=row.isNull(2)?null:row.getLong(2);int flags=row.isNull(3)?0:row.getInt(3);current();return new ProjectDocumentImport.Metadata(name,mime,size,DocumentsContract.Document.MIME_TYPE_DIR.equals(mime),(flags&DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT)!=0,(flags&DocumentsContract.Document.FLAG_PARTIAL)!=0);}catch(SecurityException denied){throw new SecurityException("系统文件读取权限已撤回，请明确重新选择");}catch(android.os.OperationCanceledException cancelled){throw new InterruptedIOException("系统文件读取已取消");}catch(RuntimeException failed){throw new IOException("所选文件元数据未能完整读取");}}
    @Override public InputStream open()throws Exception{current();try{descriptor=resolver.openAssetFileDescriptor(selected,"r",signal);if(descriptor==null)throw new IOException("所选文件没有可读字节");return descriptor.createInputStream();}catch(SecurityException denied){throw new SecurityException("系统文件读取权限已撤回，请明确重新选择");}catch(android.os.OperationCanceledException cancelled){throw new InterruptedIOException("系统文件读取已取消");}catch(IOException unreadable){throw new IOException("所选文件字节暂不可读，未导入部分内容");}}
    @Override public void close()throws Exception{if(closed)return;closed=true;signal.cancel();original.removeCancelAction(stop);if(descriptor!=null)try{descriptor.close();}catch(IOException failed){throw new IOException("系统文件读取句柄未能正常关闭");}}
}
