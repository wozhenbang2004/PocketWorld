package dev.pocketengine.vision.ocr;

import android.content.Context;
import android.content.res.AssetManager;
import dev.pocketengine.features.attachments.documents.DocumentReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;

/** Only pinned, licensed APK assets. Never downloads models or opens a user-provided data path. */
final class BundledTesseractModels {
    static final String REVISION = "87416418657359cb625c412a48b6e1d6d41c29bd";
    static final String ASSETS = "ocr/tessdata_fast";
    private static final String[] NAMES = {"eng", "chi_sim", "chi_tra"};
    private static final long[] SIZES = {4113088, 2469156, 2366642};
    private static final String[] SHA256 = {
        "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2",
        "a5fcb6f0db1e1d6d8522f39db4e848f05984669172e584e8d76b6b3141e1f730",
        "529c5b5797d64b126065cd55f2bb4c7fd7b15790798091b1ff259941a829330b"
    };
    private final Context context;
    private final AssetManager assets;
    private final boolean packaged;
    BundledTesseractModels(Context context) {
        this.context = context.getApplicationContext(); assets = this.context.getAssets();
        boolean found;
        try {
            String[] files = assets.list(ASSETS);
            found = files != null && Arrays.asList(files).containsAll(Arrays.asList("eng.traineddata", "chi_sim.traineddata", "chi_tra.traineddata", "LICENSE"));
        } catch (IOException missing) { found = false; }
        packaged = found;
    }
    boolean packaged() { return packaged; }

    File prepare(OcrJobRunner.StopSignal signal, boolean traditional) throws Exception {
        if (!packaged) throw new DocumentReader.EngineUnavailableException("Bundled local OCR models are missing");
        signal.check();
        File root = new File(context.getNoBackupFilesDir(), "ocr/tessdata-fast-" + REVISION);
        File data = new File(root, "tessdata");
        if (!data.isDirectory() && !data.mkdirs()) throw new IOException("Cannot create private OCR model directory");
        install(data, 0, signal); install(data, traditional ? 2 : 1, signal);
        signal.check(); return root;
    }

    private void install(File directory, int index, OcrJobRunner.StopSignal signal) throws Exception {
        String name = NAMES[index] + ".traineddata";
        File target = new File(directory, name);
        if (target.isFile() && target.length() == SIZES[index]) {
            try (InputStream input = new FileInputStream(target)) {
                if (digest(input, null, SIZES[index], signal).equals(SHA256[index])) return;
            }
        }
        File temporary = File.createTempFile("model-", ".tmp", directory);
        try {
            String actual;
            try (InputStream input = assets.open(ASSETS + "/" + name); FileOutputStream output = new FileOutputStream(temporary)) {
                actual = digest(input, output, SIZES[index], signal); output.getFD().sync();
            }
            if (!actual.equals(SHA256[index])) throw new DocumentReader.EngineUnavailableException("Bundled OCR model integrity check failed");
            signal.check();
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally { temporary.delete(); }
    }

    static String digest(InputStream input, FileOutputStream output, long expectedSize,
                         OcrJobRunner.StopSignal signal) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[32768]; long read = 0;
        for (int n; (n = input.read(buffer)) != -1;) {
            signal.check(); read += n;
            if (read > expectedSize) throw new DocumentReader.EngineUnavailableException("OCR model length check failed");
            digest.update(buffer, 0, n); if (output != null) output.write(buffer, 0, n);
        }
        if (read != expectedSize) throw new DocumentReader.EngineUnavailableException("OCR model length check failed");
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) hex.append(Character.forDigit((value >>> 4) & 15, 16)).append(Character.forDigit(value & 15, 16));
        return hex.toString();
    }
}
