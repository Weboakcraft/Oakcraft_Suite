package in.oakcraft.suite;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Minimal FileProvider replacement (no AndroidX needed).
 * Serves files from  <cacheDir>/shared/<name>  as  content://in.oakcraft.suite.files/<name>
 * Used for: camera capture output (EXTRA_OUTPUT) and opening / sharing exported files.
 */
public class ShareProvider extends ContentProvider {

    public static final String AUTHORITY = "in.oakcraft.suite.files";

    public static File dir(Context ctx) {
        File d = new File(ctx.getCacheDir(), "shared");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** Safe file name: strip path separators and odd characters. */
    public static String safeName(String name) {
        if (name == null) name = "file";
        String n = name.replace('\\', '_').replace('/', '_').replaceAll("[^A-Za-z0-9._ ()-]", "_").trim();
        if (n.length() == 0 || n.startsWith(".")) n = "file_" + n;
        if (n.length() > 120) n = n.substring(n.length() - 120);
        return n;
    }

    public static File fileFor(Context ctx, String name) {
        return new File(dir(ctx), safeName(name));
    }

    public static Uri uriFor(String name) {
        return Uri.parse("content://" + AUTHORITY + "/" + Uri.encode(safeName(name)));
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null) throw new FileNotFoundException("no name");
        File f = new File(dir(getContext()), safeName(name));
        return f;
    }

    @Override
    public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = resolve(uri);
        int m = ParcelFileDescriptor.parseMode(mode);
        return ParcelFileDescriptor.open(f, m);
    }

    @Override
    public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name == null) return "application/octet-stream";
        int i = name.lastIndexOf('.');
        String ext = i >= 0 ? name.substring(i + 1).toLowerCase() : "";
        String t = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        if (t == null) {
            if ("xlsx".equals(ext)) t = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            else if ("xls".equals(ext)) t = "application/vnd.ms-excel";
            else if ("csv".equals(ext)) t = "text/csv";
            else if ("json".equals(ext)) t = "application/json";
            else t = "application/octet-stream";
        }
        return t;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File f;
        try { f = resolve(uri); } catch (FileNotFoundException e) { return null; }
        if (projection == null) projection = new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE };
        MatrixCursor c = new MatrixCursor(projection, 1);
        Object[] row = new Object[projection.length];
        for (int i = 0; i < projection.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(projection[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(projection[i])) row[i] = Long.valueOf(f.length());
            else if ("_data".equals(projection[i])) row[i] = f.getAbsolutePath();
            else if ("mime_type".equals(projection[i])) row[i] = getType(uri);
            else row[i] = null;
        }
        c.addRow(row);
        return c;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        try { File f = resolve(uri); return f.delete() ? 1 : 0; } catch (FileNotFoundException e) { return 0; }
    }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
