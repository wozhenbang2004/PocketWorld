package dev.pocketengine.features.updates;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/** Read grants expose exactly one authenticated APK, never the directory, manifest or a partial download. */
public final class AppUpdateProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    private File file(Uri uri) throws Exception {
        if (!"content".equals(uri.getScheme()) || !getContext().getPackageName().concat(".updates").equals(uri.getAuthority())
                || uri.getPathSegments().size() != 1 || uri.getQuery() != null || uri.getFragment() != null)
            throw new SecurityException("更新文件地址无效");
        return AppUpdateManager.get(getContext()).verifiedForProvider(UpdateManifest.digest(uri.getLastPathSegment()));
    }
    @Override public String getType(Uri uri) { return "application/vnd.android.package-archive"; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("更新文件只读");
        try { return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY); }
        catch (Exception unavailable) { throw new FileNotFoundException("更新文件未通过校验或已过期"); }
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] arguments, String sortOrder) {
        try {
            File file = file(uri);
            MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            cursor.addRow(new Object[]{"PocketWorld-update.apk", file.length()}); return cursor;
        } catch (Exception unavailable) { throw new IllegalArgumentException("更新文件不可用"); }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("只读更新文件"); }
    @Override public int delete(Uri uri, String selection, String[] arguments) { throw new UnsupportedOperationException("只读更新文件"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] arguments) { throw new UnsupportedOperationException("只读更新文件"); }
}
