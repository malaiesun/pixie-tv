package dev.malaiesun.home;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.regex.Pattern;

/**
 * Lets adb read/write config.json and wallpapers in the app's files dir (Android 14 blocks adb from
 * /sdcard/Android/data). Guarded by WRITE_SECURE_SETTINGS in the manifest: adb's shell user holds it,
 * ordinary apps cannot.
 *
 *   adb shell content write --uri content://dev.malaiesun.home.config/config.json < config.json
 *   adb shell content read  --uri content://dev.malaiesun.home.config/config.json > config.json
 */
public final class ConfigProvider extends ContentProvider {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}\\.(json|jpg|jpeg|png|webp)");

    @Override public boolean onCreate() { return true; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || !NAME.matcher(name).matches() || name.startsWith(".")) throw new FileNotFoundException("bad name");
        File dir = getContext().getExternalFilesDir(null);
        if (dir == null) throw new FileNotFoundException("no files dir");
        if (!dir.exists()) dir.mkdirs();
        // A write replaces the file: "w" alone doesn't truncate on newer Android, which left the old
        // file's tail behind when the new content was shorter. Reads ("r") never truncate.
        int m = ParcelFileDescriptor.parseMode(mode);
        if (mode.contains("w") && !mode.contains("a")) m |= ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE;
        if ((m & ParcelFileDescriptor.MODE_WRITE_ONLY) != 0 || (m & ParcelFileDescriptor.MODE_READ_WRITE) != 0) {
            // adb push finished: tell Home and the agent (same process) to reload
            try {
                return ParcelFileDescriptor.open(new File(dir, name), m, new android.os.Handler(android.os.Looper.getMainLooper()),
                        e -> Config.changed());
            } catch (java.io.IOException e) {
                throw new FileNotFoundException(e.getMessage());
            }
        }
        return ParcelFileDescriptor.open(new File(dir, name), m);
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
