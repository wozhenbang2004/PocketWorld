package dev.pocketengine.features.local.project.android;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.provider.DocumentsContract;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.local.project.ProjectSourceImport;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Read-only lifetime of the exact tree chosen in the native document picker.
 * No persisted URI permission, JSON URI transport, filesystem path conversion or model entrypoint. */
public final class AndroidProjectSourceTree implements ProjectSourceImport.Tree,AutoCloseable {
    private final ContentResolver resolver;private final Uri tree;private final RunControl control;
    private final String root;private final CancellationSignal signal=new CancellationSignal();
    private final Runnable cancel=signal::cancel;private final Map<String,ProjectSourceImport.Entry> issued=new HashMap<>();
    private boolean closed;private String label="已有源码";
    public AndroidProjectSourceTree(Context context,Uri ownerSelectedTree,RunControl original)throws Exception {
        if(ownerSelectedTree==null||!"content".equals(ownerSelectedTree.getScheme())||ownerSelectedTree.getAuthority()==null||!DocumentsContract.isTreeUri(ownerSelectedTree))throw new SecurityException("请在系统文件选择器明确选择源码目录");
        resolver=context.getContentResolver();tree=ownerSelectedTree;control=java.util.Objects.requireNonNull(original);root=DocumentsContract.getTreeDocumentId(tree);control.onCancel(cancel);
        try {current();Uri document=DocumentsContract.buildDocumentUriUsingTree(tree,root);
            try(Cursor row=resolver.query(document,new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE},null,null,null,signal)){
                if(row==null||!row.moveToFirst()||!DocumentsContract.Document.MIME_TYPE_DIR.equals(row.getString(1)))throw new SecurityException("系统没有返回可读源码目录");label=row.getString(0);
            }
        }catch(Exception failed){close();throw failed;}
    }
    private void current()throws Exception{if(closed)throw new IllegalStateException("原源码目录读取已结束");control.check();if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("源码目录读取已取消");}
    public String sourceLabel(){return label;}
    @Override public String rootHandle(){return root;}
    @Override public List<ProjectSourceImport.Entry> children(String directory)throws Exception {
        current();ProjectSourceImport.Entry retained=issued.get(directory);if(!root.equals(directory)&&(retained==null||!retained.directory))throw new SecurityException("不是这次原目录实际列出的子目录");
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,directory);List<ProjectSourceImport.Entry> result=new ArrayList<>();
        String[] fields={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_FLAGS};
        try(Cursor rows=resolver.query(children,fields,null,null,null,signal)){
            if(rows==null)throw new IllegalStateException("所选目录没有完整读取结果，未导入部分工程");
            while(rows.moveToNext()){
                current();if(result.size()>=ProjectSourceImport.MAX_ENTRIES)throw new IllegalArgumentException("来源目录条目超过10000");String id=rows.getString(0),name=rows.getString(1);String mime=rows.getString(2);if(id==null||name==null||mime==null)throw new IllegalArgumentException("来源目录含缺少身份、名称或类型的文件");int flags=rows.isNull(4)?0:rows.getInt(4);
                ProjectSourceImport.Entry entry=new ProjectSourceImport.Entry(id,name,DocumentsContract.Document.MIME_TYPE_DIR.equals(mime),rows.isNull(3)||rows.getLong(3)<0?null:rows.getLong(3),(flags&DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT)!=0,(flags&DocumentsContract.Document.FLAG_PARTIAL)!=0);result.add(entry);issued.put(id,entry);
            }
        }
        current();return result;
    }
    @Override public InputStream open(ProjectSourceImport.Entry entry)throws Exception {
        current();if(entry==null||entry.directory||issued.get(entry.handle)!=entry)throw new SecurityException("文件不是当前所选目录这次实际读取的原对象");
        Uri document=DocumentsContract.buildDocumentUriUsingTree(tree,entry.handle);AssetFileDescriptor descriptor=resolver.openAssetFileDescriptor(document,"r",signal);if(descriptor==null)throw new IllegalStateException("所选源码文件无法读取");InputStream input;
        try{input=descriptor.createInputStream();}catch(Exception failed){descriptor.close();throw failed;}
        Runnable stop=()->{try{input.close();}catch(Exception ignored){}};control.onCancel(stop);
        return new FilterInputStream(input){private boolean ended;@Override public int read()throws java.io.IOException{check();return super.read();}@Override public int read(byte[] value,int offset,int length)throws java.io.IOException{check();return super.read(value,offset,length);}private void check()throws java.io.IOException{try{current();}catch(Exception cancelled){throw new java.io.InterruptedIOException("原源码读取已取消或失效");}}@Override public void close()throws java.io.IOException{if(ended)return;ended=true;control.removeCancelAction(stop);super.close();}};
    }
    @Override public void close(){if(closed)return;closed=true;signal.cancel();control.removeCancelAction(cancel);issued.clear();}
}
