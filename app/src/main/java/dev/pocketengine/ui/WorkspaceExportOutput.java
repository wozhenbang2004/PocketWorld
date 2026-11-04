package dev.pocketengine.ui;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Writes one prepared owner export, without retrying or changing its source. */
final class WorkspaceExportOutput {
    private WorkspaceExportOutput() { }

    @FunctionalInterface
    interface Destination {
        OutputStream open() throws Exception;
    }

    static void write(File source, Destination destination) throws Exception {
        boolean destinationAttempted = false;
        try (InputStream input = new FileInputStream(source)) {
            // A provider may truncate in open itself, even if it never returns a stream.
            destinationAttempted = true;
            try (OutputStream output = destination.open()) {
                if (output == null) throw new IOException("无法写入选择的位置");
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                output.flush();
            }
        } catch (Exception failure) {
            if (!destinationAttempted) throw failure;
            throw new IOException("保存结果未确认：所选目标文件可能已被截断或仅写入部分内容。请检查目标文件；原工作区内容未修改，未自动重试。原因："
                    + (failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()), failure);
        }
    }
}
