package dev.pocketengine.vision.ocr;

import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.documents.DocumentReader;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Android's decoder applies EXIF rotations/reflections once, before image-local OCR geometry. */
final class OcrImageDecoder {
    static final class Raster implements AutoCloseable {
        final Bitmap bitmap;
        final int sourceWidth, sourceHeight;
        Raster(Bitmap bitmap, int sourceWidth, int sourceHeight) {
            this.bitmap = bitmap; this.sourceWidth = sourceWidth; this.sourceHeight = sourceHeight;
        }
        public void close() { bitmap.recycle(); }
    }

    static Raster decode(byte[] bytes, String format, RunControl control) throws Exception {
        if (bytes == null || bytes.length == 0) throw new DocumentReader.InvalidDocumentException("Empty OCR image");
        if (bytes.length > DocumentReader.MAX_INPUT_BYTES) throw new DocumentReader.DocumentLimitException("OCR input exceeds 20 MiB");
        String mime = "png".equals(format) ? "image/png" : "jpeg".equals(format) ? "image/jpeg" : "webp".equals(format) ? "image/webp" : "";
        if (mime.isEmpty()) throw new DocumentReader.InvalidDocumentException("Unsupported OCR image format");
        control.checkpoint();
        int[] dimensions = new int[2];
        Bitmap bitmap;
        try {
            bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), (decoder, info, source) -> {
                if (!mime.equals(info.getMimeType()) || info.isAnimated())
                    throw new DecodeRejected(false, "OCR requires one static image of the declared format");
                int width = info.getSize().getWidth(), height = info.getSize().getHeight();
                int[] target;
                try { target = OcrPage.rasterSize(width, height); }
                catch (IllegalArgumentException limit) { throw new DecodeRejected(true, "OCR image dimensions exceed the bounded decoder limit"); }
                dimensions[0] = width; dimensions[1] = height;
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
                decoder.setTargetSize(target[0], target[1]);
                decoder.setPostProcessor(canvas -> { canvas.drawColor(Color.WHITE, PorterDuff.Mode.DST_OVER); return PixelFormat.OPAQUE; });
                decoder.setOnPartialImageListener(error -> false);
            });
        } catch (DecodeRejected rejected) {
            if (rejected.limit) throw new DocumentReader.DocumentLimitException(rejected.getMessage());
            throw new DocumentReader.InvalidDocumentException(rejected.getMessage());
        } catch (IOException | IllegalArgumentException invalid) {
            control.checkpoint();
            throw new DocumentReader.InvalidDocumentException("OCR image could not be decoded completely");
        }
        try {
            control.checkpoint();
            if (bitmap.getConfig() != Bitmap.Config.ARGB_8888) {
                Bitmap normalized = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                bitmap.recycle();
                if (normalized == null) throw new DocumentReader.EngineUnavailableException("OCR image could not be normalized to an 8-bit raster");
                bitmap = normalized;
            }
            if (bitmap.getWidth() > OcrPage.MAX_SIDE || bitmap.getHeight() > OcrPage.MAX_SIDE
                    || (long) bitmap.getWidth() * bitmap.getHeight() > OcrPage.MAX_PIXELS)
                throw new DocumentReader.DocumentLimitException("Decoded OCR raster exceeds pixel budget");
            return new Raster(bitmap, dimensions[0], dimensions[1]);
        } catch (Exception failure) { bitmap.recycle(); throw failure; }
    }

    private static final class DecodeRejected extends RuntimeException {
        final boolean limit;
        DecodeRejected(boolean limit, String message) { super(message); this.limit = limit; }
    }
}
