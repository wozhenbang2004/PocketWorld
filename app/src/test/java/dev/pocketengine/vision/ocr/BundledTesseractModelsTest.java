package dev.pocketengine.vision.ocr;

import android.app.Application;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.documents.DocumentReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=35,application=Application.class)
public final class BundledTesseractModelsTest {
    private static Path assets(){
        Path root=Path.of("app/src/main/assets/ocr/tessdata_fast");
        return Files.isDirectory(root)?root:Path.of("src/main/assets/ocr/tessdata_fast");
    }
    @Test public void actualBundledModelsMatchTheirPinnedManifestAndProductionDigest()throws Exception {
        JSONObject manifest=new JSONObject(Files.readString(assets().resolve("manifest.json")));
        assertEquals(BundledTesseractModels.REVISION,manifest.getString("commit"));assertEquals("Apache-2.0",manifest.getString("license"));
        JSONArray items=manifest.getJSONArray("models");assertEquals(4,items.length());
        for(int i=0;i<items.length();i++){
            JSONObject item=items.getJSONObject(i);Path file=assets().resolve(item.getString("name"));assertEquals(item.getLong("bytes"),Files.size(file));
            try(InputStream input=Files.newInputStream(file)){assertEquals(item.getString("sha256"),BundledTesseractModels.digest(input,null,item.getLong("bytes"),new OcrJobRunner.StopSignal(new RunControl(),30000)));}
        }
    }
    @Test public void modelLengthMismatchCannotBeAcceptedAsReady()throws Exception {
        assertThrows(DocumentReader.EngineUnavailableException.class,()->BundledTesseractModels.digest(new ByteArrayInputStream(new byte[]{1,2}),null,1,new OcrJobRunner.StopSignal(new RunControl(),1000)));
        assertThrows(DocumentReader.EngineUnavailableException.class,()->BundledTesseractModels.digest(new ByteArrayInputStream(new byte[]{1}),null,2,new OcrJobRunner.StopSignal(new RunControl(),1000)));
    }
    @Test public void cancelledPreparationNeverCompletesTheDigest()throws Exception {
        RunControl control=new RunControl();control.cancel();
        assertThrows(InterruptedException.class,()->BundledTesseractModels.digest(new ByteArrayInputStream(new byte[]{1}),null,1,new OcrJobRunner.StopSignal(control,1000)));
    }
}
