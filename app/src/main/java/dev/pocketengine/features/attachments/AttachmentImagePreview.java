package dev.pocketengine.features.attachments;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import dev.pocketengine.core.RunControl;

/** Bounded display derivative of verified original bytes, never a substitute file or proof of content quality. */
public final class AttachmentImagePreview {
    private AttachmentImagePreview(){}
    public static Bitmap decode(byte[] bytes,RunControl control)throws Exception {
        control.check();if(bytes==null||bytes.length>AttachmentBook.MAX_FILE_BYTES)throw new IllegalArgumentException("图片超过预览大小限制");
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);
        if(bounds.outWidth<1||bounds.outHeight<1||bounds.outWidth>100000||bounds.outHeight>100000)throw new IllegalArgumentException("图片尺寸无法有界预览；原文件仍可导出");
        BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;while(bounds.outWidth/options.inSampleSize>1024||bounds.outHeight/options.inSampleSize>1024)options.inSampleSize*=2;options.inPreferredConfig=Bitmap.Config.ARGB_8888;
        control.check();Bitmap image=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);if(image==null)throw new IllegalArgumentException("图片解码未取得可用预览");
        try{control.check();return image;}catch(Exception stopped){image.recycle();throw stopped;}
    }
}
