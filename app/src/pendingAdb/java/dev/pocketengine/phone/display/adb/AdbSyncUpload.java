package dev.pocketengine.phone.display.adb;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Independently implemented bounded ADB SYNC v1 SEND, using the published protocol. */
public final class AdbSyncUpload {
    public static final String HELPER_PATH="/data/local/tmp/pocketengine-display-helper.jar";
    public static final String HELPER_COMMAND="shell:CLASSPATH="+HELPER_PATH+" app_process /system/bin dev.pocketengine.displayhelper.Main";
    private AdbSyncUpload(){}
    public static void send(InputStream input,OutputStream output,byte[] helper,long modifiedSeconds)throws IOException {
        if(helper==null||helper.length<4||helper.length>524288)throw new IOException("后台助手文件大小无效");
        if(modifiedSeconds<0||modifiedSeconds>0xffffffffL)throw new IOException("后台助手时间超出SYNC协议边界");
        if(helper[0]!='P'||helper[1]!='K')throw new IOException("后台助手不是打包的dex jar");
        byte[] path=(HELPER_PATH+",33152").getBytes(StandardCharsets.UTF_8);frame(output,"SEND",path.length,path);
        for(int offset=0;offset<helper.length;offset+=65536){int count=Math.min(65536,helper.length-offset);byte[] chunk=new byte[count];System.arraycopy(helper,offset,chunk,0,count);frame(output,"DATA",count,chunk);}
        frame(output,"DONE",modifiedSeconds&0xffffffffL,new byte[0]);
        DataInputStream response=new DataInputStream(input);byte[] tag=new byte[4];response.readFully(tag);long length=readLittle(response);
        String result=new String(tag,StandardCharsets.US_ASCII);
        if(result.equals("OKAY")&&length==0){frame(output,"QUIT",0,new byte[0]);return;}
        if(result.equals("FAIL")&&length<=4096){byte[] ignored=new byte[(int)length];response.readFully(ignored);throw new IOException("设备拒绝了后台助手上传");}
        throw new IOException("ADB上传没有返回有效确认，结果待核对");
    }
    private static void frame(OutputStream output,String tag,long length,byte[] payload)throws IOException {
        ByteArrayOutputStream frame=new ByteArrayOutputStream(8+payload.length);frame.write(tag.getBytes(StandardCharsets.US_ASCII));
        for(int i=0;i<4;i++)frame.write((int)(length>>>(8*i))&255);frame.write(payload);
        // One flushed write avoids withholding an ADB WRTE acknowledgement behind a buffered header.
        output.write(frame.toByteArray());output.flush();
    }
    private static long readLittle(DataInputStream input)throws IOException {long value=0;for(int i=0;i<4;i++)value|=(long)input.readUnsignedByte()<<(8*i);return value;}
}
