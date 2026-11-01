package dev.pocketengine.ui;

import android.content.res.Resources;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/** Standalone renderer only: IDs/text generated from the locked official AAR R.txt/res XML.
 * A normal Android resource build uses the real resources. No product/Host/Runner is replaced. */
@Implements(Resources.class)
public final class OfficialAarResources extends org.robolectric.shadows.ShadowResources {
    @RealObject private Resources resources;
    private JSONObject strings;
    @Implementation protected CharSequence getText(int id) {
        String path=System.getProperty("pocketworld.validation.aarStrings");
        if(path!=null)try{
            if(strings==null)strings=new JSONObject(Files.readString(Path.of(path)));
            String key=Integer.toString(id);if(strings.has(key))return strings.getString(key);
        }catch(Exception failure){throw new IllegalStateException("Official AAR validation resources could not be read",failure);}
        return Shadow.directlyOn(resources,Resources.class,"getText",ClassParameter.from(int.class,id));
    }
}
