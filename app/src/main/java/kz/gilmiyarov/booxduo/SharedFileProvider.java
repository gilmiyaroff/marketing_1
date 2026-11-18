package kz.gilmiyarov.booxduo;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Read-only URI bridge so external readers can open files without file:// exposure. */
public class SharedFileProvider extends ContentProvider {
    static final String AUTHORITY = "kz.gilmiyarov.booxduo.files";

    static Uri uriFor(File f) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY)
                .appendPath("open").appendQueryParameter("path", f.getAbsolutePath()).build();
    }

    private File validated(Uri uri) throws FileNotFoundException {
        if (uri == null || !"open".equals(uri.getLastPathSegment()))
            throw new FileNotFoundException("Invalid URI");
        String path = uri.getQueryParameter("path");
        if (path == null) throw new FileNotFoundException("No file");
        try {
            File root = Environment.getExternalStorageDirectory().getCanonicalFile();
            File file = new File(path).getCanonicalFile();
            // Content must remain within shared storage and must be read-only.
            if (!file.toPath().startsWith(root.toPath()) || !file.isFile() || !file.canRead())
                throw new FileNotFoundException("Not accessible");
            return file;
        } catch (IOException e) {
            throw new FileNotFoundException("Bad file");
        }
    }
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) {
        try { return FilesCatalog.mime(validated(uri)); }
        catch (Exception e) { return "application/octet-stream"; }
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only provider");
        return ParcelFileDescriptor.open(validated(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        File f;
        try { f = validated(uri); } catch (FileNotFoundException e) { return null; }
        if (projection == null) projection = new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor cursor = new MatrixCursor(projection);
        Object[] row = new Object[projection.length];
        for (int i=0;i<projection.length;i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(projection[i])) row[i]=f.getName();
            if (OpenableColumns.SIZE.equals(projection[i])) row[i]=f.length();
        }
        cursor.addRow(row);
        return cursor;
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
