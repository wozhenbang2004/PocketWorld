package dev.pocketengine.vision.ocr;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.documents.DocumentContent;
import dev.pocketengine.features.attachments.documents.DocumentReader;
import java.util.Map;

/** One local image engine shared by attachment reads and read-only phone observations. */
public interface LocalImageOcr extends DocumentReader.OcrPort, AutoCloseable {
    OcrPage recognizeImage(byte[] image, String format, RunControl control) throws Exception;

    @Override default void recognize(byte[] image, String format, DocumentReader.RequestSink sink,
                                     RunControl control) throws Exception {
        control.checkpoint();
        // The PDF renderer remaps this image's unit 1 to the original requested page number.
        // Its sink intentionally retains that PDF request (whose startUnit may be greater than 1).
        if (sink.saturated()) return;
        OcrPage page = recognizeImage(image, format, control);
        control.checkpoint();
        sink.totalUnits(1);
        if (page.downsampled()) sink.warning("OCR image was downsampled to the raster pixel budget; small text can be missed");
        if (page.lines.isEmpty()) sink.warning("Local OCR completed without recognized text; this is not proof that the image is blank");
        for (OcrPage.Line line : page.lines) {
            control.checkpoint();
            Map<String,String> location = DocumentContent.location("image", "1", "line", Integer.toString(line.index),
                    "coordinateSpace", OcrPage.COORDINATE_SPACE, "boundsPixels", line.boundsPixels(),
                    "rasterWidth", Integer.toString(page.width), "rasterHeight", Integer.toString(page.height),
                    "orientedSourceWidth", Integer.toString(page.sourceWidth), "orientedSourceHeight", Integer.toString(page.sourceHeight),
                    "orientation", "platform_exif_applied", "engine", engineVersion());
            sink.text(1, "ocr_text", location, line.text);
            if (sink.saturated()) break;
        }
        control.checkpoint();
    }

    @Override void close();
}
