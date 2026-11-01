package dev.pocketengine.phone.display;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Version-one IPC framing shared by our app and our separately dexed helper. */
public final class DisplayWire {
    private DisplayWire() { }
    public static String read(DataInputStream input,int limit)throws IOException {
        int length=input.readInt();if(length<2||length>limit)throw new IOException("Invalid display frame size");
        byte[] bytes=new byte[length];input.readFully(bytes);
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    public static void write(DataOutputStream output,String value,int limit)throws IOException {
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);if(bytes.length<2||bytes.length>limit)throw new IOException("Display frame exceeds limit");
        output.writeInt(bytes.length);output.write(bytes);output.flush();
    }
}
