package dev.pocketengine.features.devices;

import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.decoder.Mode;
import com.google.zxing.qrcode.encoder.Encoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.json.JSONObject;

/** Presentation of the one invitation already returned by native pairing. Never mints, shortens or saves one. */
public final class PairingInvitationDisplay {
    private PairingInvitationDisplay() {}
    public static JSONObject project(String original,long now)throws Exception{
        byte[] utf8=original.getBytes(StandardCharsets.UTF_8);
        JSONObject invite=DeviceProtocol.parseObject(utf8);
        PairingCoordinator.validateInvitationForPreview(invite,now);
        JSONObject view=new JSONObject().put("kind","invitation").put("displayOnce",true).put("invitation",original)
                .put("expiresAt",invite.getLong("expiresAt"));
        if(utf8.length>2200)return view; // Same single-symbol byte bound as the local receiver. Keep the original text.
        try {
            // JSON is BYTE mode. Pass the ORIGINAL string in UTF-8, without compact/multi-segment mode or reserialization.
            var qr=Encoder.encode(original,ErrorCorrectionLevel.M,Map.of(EncodeHintType.CHARACTER_SET,"UTF-8"));
            var matrix=qr.getMatrix();
            if(qr.getMode()!=Mode.BYTE||matrix==null||matrix.getWidth()!=matrix.getHeight())return view;
            int n=matrix.getWidth(),side=n+8;
            if(n<21||n>177||(n-21)%4!=0)return view;
            byte[] modules=new byte[(side*side+7)/8]; // Four untouched white modules on every edge.
            for(int y=0;y<n;y++)for(int x=0;x<n;x++)if(matrix.get(x,y)==1){
                int i=(y+4)*side+x+4;modules[i>>>3]|=(byte)(128>>>(i&7));
            }
            view.put("qr",new JSONObject().put("modules",side).put("bits",Base64.getEncoder().encodeToString(modules)));
        }catch(WriterException cannotFit){ /* Text still carries the complete and unchanged invitation. */ }
        return view;
    }
}
